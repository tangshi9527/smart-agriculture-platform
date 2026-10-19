package com.smartagri.ingest;

import com.smartagri.command.CommandService;
import com.smartagri.common.JsonUtil;
import com.smartagri.device.DeviceShadowService;
import com.smartagri.device.Telemetry;
import com.smartagri.mq.TelemetryEvent;
import com.smartagri.mq.TelemetryMessageBus;
import com.smartagri.mqtt.session.DeviceSessionRegistry;
import com.smartagri.websocket.WebSocketPushService;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接入网关联接层：将 Netty IO 线程收到的设备报文桥接到 Spring 业务服务。
 * <p>
 * 解耦设计（P2-1）：接入层只负责「连接生命周期 / 设备身份 / 设备影子（实时孪生 + 指令路由）」，
 * 以及把遥测封装为 {@link TelemetryEvent} 发布到 {@link TelemetryMessageBus}。
 * 下游的「时序持久化 / 告警评估 / 实时推送」由三个独立消费者异步处理，彼此隔离——
 * 任一消费者抖动或故障都不会阻塞设备接入，也不会牵连另外两条处理链路。
 * <p>
 * 设备身份以遥测 topic（device/{deviceId}/telemetry）中的 deviceId 为准，
 * 与 TCP 连接 ID 解耦，从而支持脚本模拟器用唯一连接 ID 承载多设备。
 */
@Service
public class IngestGateway {

    private static final Logger log = LoggerFactory.getLogger(IngestGateway.class);

    private final DeviceShadowService shadow;
    private final TelemetryMessageBus bus;
    private final WebSocketPushService push;
    private final DeviceSessionRegistry registry;
    private final CommandService commandService;

    /**
     * 字段级：被剔除但整帧仍发布的非法数值个数（NaN / Infinity / 1e999）。
     *
     * <p>这一路<b>不丢帧</b>——同一帧里其他字段照常入库。上一版把这条和「整帧丢弃」
     * 合并成一个 {@code invalidValues} 计数，文档里又写成"整帧丢弃"，是错的：
     * 一个 6 字段帧里坏 1 个，5 个指标仍然进库，数据是"稀疏"不是"缺失"。
     * 两者对查询端的影响差一个数量级，必须分开。</p>
     */
    private final java.util.concurrent.atomic.AtomicLong invalidValuesStripped =
            new java.util.concurrent.atomic.AtomicLong(0);

    /** 帧级：整帧没有任何有效指标，被整体丢弃 */
    private final java.util.concurrent.atomic.AtomicLong framesAllInvalidDropped =
            new java.util.concurrent.atomic.AtomicLong(0);

    /** 帧级：JSON 本身解析失败 / 不是对象，整帧丢弃 */
    private final java.util.concurrent.atomic.AtomicLong framesUnparsableDropped =
            new java.util.concurrent.atomic.AtomicLong(0);

    /** 最近一个吐脏数据的设备与时间：光有总数只能知道"有人在吐"，不知道"谁在吐" */
    private volatile String lastInvalidDevice;
    private volatile long lastInvalidAt;

    /** 连接 ID -> 最近上报的设备 ID（用于断线时精准下线对应设备） */
    private final ConcurrentHashMap<String, String> connDevice = new ConcurrentHashMap<>();

    public IngestGateway(DeviceShadowService shadow, TelemetryMessageBus bus,
                         WebSocketPushService push, DeviceSessionRegistry registry,
                         CommandService commandService) {
        this.shadow = shadow;
        this.bus = bus;
        this.push = push;
        this.registry = registry;
        this.commandService = commandService;
    }

    public void onConnect(Channel ch) {
        registry.register(ch.id().asLongText(), ch);
        pushStatus();
    }

    public void onTelemetry(Channel ch, String topic, byte[] payload) {
        String deviceId = extractDeviceId(topic);
        if (deviceId == null) {
            deviceId = ch.id().asLongText();
        }
        connDevice.put(ch.id().asLongText(), deviceId);
        // 维护下行路由表：设备每次上报即确认其活跃通道（幂等）
        commandService.bind(deviceId, ch);
        Telemetry t = parse(deviceId, payload);
        if (t == null) {
            return;
        }
        // 设备影子（实时孪生 / 指令路由）保留在接入层，低延迟更新；
        // 持久化 / 告警 / 实时推送 解耦到消息总线，由独立消费者异步处理，不阻塞接入线程。
        shadow.markOnline(deviceId, t);
        bus.publish(TelemetryEvent.from(t));
    }

    public void onPing(Channel ch) {
        String deviceId = connDevice.get(ch.id().asLongText());
        if (deviceId != null) {
            shadow.markOnline(deviceId, null); // 心跳保活，刷新最后心跳时间
        }
    }

    public void onDisconnect(Channel ch) {
        String deviceId = connDevice.remove(ch.id().asLongText());
        registry.unregister(ch.id().asLongText(), ch);
        commandService.unbind(ch);
        if (deviceId != null) {
            shadow.markOffline(deviceId);
            log.info("设备下线: {} (连接数={})", deviceId, registry.connectionCount());
        }
        pushStatus();
    }

    /** 从 topic 提取设备 ID：device/{deviceId}/telemetry */
    private String extractDeviceId(String topic) {
        if (topic == null) {
            return null;
        }
        if (topic.startsWith("device/") && topic.endsWith("/telemetry")) {
            return topic.substring("device/".length(), topic.length() - "/telemetry".length());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    /** 包可见：单测直接验证数值清洗，不必起 Netty 连接 */
    Telemetry parse(String deviceId, byte[] payload) {
        Map<String, Object> map;
        try {
            String json = new String(payload, StandardCharsets.UTF_8);
            map = JsonUtil.fromJson(json, Map.class);
        } catch (Exception ex) {
            framesUnparsableDropped.incrementAndGet();
            log.warn("遥测解析失败 device={}: {}", deviceId, ex.getMessage());
            return null;
        }
        if (map == null) {
            framesUnparsableDropped.incrementAndGet();
            return null;
        }
        try {
            Telemetry t = new Telemetry();
            t.setDeviceId(deviceId);
            t.setTs(System.currentTimeMillis());
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object v = e.getValue();
                if (!(v instanceof Number n)) {
                    continue;
                }
                double d = n.doubleValue();
                // NaN / ±Infinity / 1e999 都能通过 JSON 解析，但 InfluxDB 会拒绝这类字段值。
                // 一条脏数据就会让整次写入抛异常 → 触发全局降级，等于"一台坏传感器打死整个存储"。
                // 低价 DHT22 / 电容式土壤湿度计产生 NaN 是常态，必须在这里拦住。
                if (!Double.isFinite(d)) {
                    long bad = invalidValuesStripped.incrementAndGet();
                    lastInvalidDevice = deviceId;
                    lastInvalidAt = System.currentTimeMillis();
                    if (bad == 1 || bad % 1000 == 0) {
                        log.warn("剔除非法数值 device={} field={} value={}（累计剔除 {} 个字段）",
                                deviceId, e.getKey(), d, bad);
                    }
                    continue;
                }
                t.putMetric(e.getKey(), d);
            }
            if (t.getMetrics().isEmpty()) {
                // 整帧一个有效指标都没有：这一帧是真的要丢，和上面的"剔除字段"不是一回事
                long n = framesAllInvalidDropped.incrementAndGet();
                lastInvalidDevice = deviceId;
                lastInvalidAt = System.currentTimeMillis();
                if (n == 1 || n % 1000 == 0) {
                    log.warn("整帧无有效指标，已丢弃 device={}（累计丢弃 {} 帧）", deviceId, n);
                }
                return null;
            }
            return t;
        } catch (Exception ex) {
            framesUnparsableDropped.incrementAndGet();
            log.warn("遥测解析失败 device={}: {}", deviceId, ex.getMessage());
            return null;
        }
    }

    /** 字段级剔除数（整帧仍可能已入库） */
    public long invalidValues() {
        return invalidValuesStripped.get();
    }

    /** 帧级：整帧全是脏数据被丢弃的帧数 */
    public long framesAllInvalidDropped() {
        return framesAllInvalidDropped.get();
    }

    /** 帧级：JSON 解析失败被丢弃的帧数 */
    public long framesUnparsableDropped() {
        return framesUnparsableDropped.get();
    }

    /** health 用的一整块：三类计数分开给，别让调用方自己去猜 */
    public java.util.Map<String, Object> sanitizeStats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("invalidValuesStripped", invalidValuesStripped.get());
        m.put("framesAllInvalidDropped", framesAllInvalidDropped.get());
        m.put("framesUnparsableDropped", framesUnparsableDropped.get());
        m.put("lastInvalidDevice", lastInvalidDevice);
        m.put("lastInvalidAt", lastInvalidAt);
        return m;
    }

    private void pushStatus() {
        push.pushStatus(java.util.Map.of(
                "connections", registry.connectionCount(),
                "devices", shadow.onlineCount(),
                "online", shadow.onlineCount(),
                "dashboards", push.onlineDashboards(),
                "ts", System.currentTimeMillis()));
    }

    /**
     * 周期性广播设备/连接/大屏统计（每秒）。
     * 解决：设备已稳定、无新连接事件时，大屏 KPI 不会更新；以及页面刷新/WS 断线重连后
     * 拿不到当前状态（此前 status 帧仅在设备 connect/disconnect 时触发）。
     */
    @Scheduled(fixedRate = 1000)
    public void broadcastStatus() {
        pushStatus();
    }
}
