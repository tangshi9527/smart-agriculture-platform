package com.smartagri.command;

import com.smartagri.common.Constants;
import com.smartagri.common.JsonUtil;
import com.smartagri.device.DeviceShadowService;
import com.smartagri.mqtt.codec.MqttMessage;
import com.smartagri.websocket.WebSocketPushService;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 设备控制指令服务：云管端一体化中的「控制闭环」中枢。
 * <p>
 * 职责：
 * 1. 维护「设备 ID → 活跃 MQTT 通道」路由表，使云端能够反向（下行）向指定设备下发指令；
 * 2. 管理指令全生命周期（PENDING/SENT/EXECUTED/FAILED/NO_SESSION/TIMEOUT）；
 * 3. 接收设备执行回执（device/{id}/command/response），闭环更新影子并执行状态、推送前端；
 * 4. 周期性扫描：已下发但超时未回执的指令置为 TIMEOUT。
 * <p>
 * 设计取舍：复用现有 MQTT 通道（Netty Channel）直接编码下行 PUBLISH，不另建推送通道，
 * 与既有遥测上行共用同一套编解码，减少重复实现。
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    /** 回执超时阈值（毫秒）：超过即判定指令丢失 */
    private static final long RESPONSE_TIMEOUT_MS = 8000;
    /** 执行器字段（影子中持久化的控制状态） */
    private static final List<String> CONTROL_NAMES = Arrays.asList("fan", "valve", "lamp", "pump");

    private final DeviceShadowService shadow;
    private final WebSocketPushService push;
    private final SecureRandom rnd = new SecureRandom();

    /** 设备 ID -> 承载该设备的活跃通道集合（下行路由表） */
    private final ConcurrentHashMap<String, Set<Channel>> deviceChannels = new ConcurrentHashMap<>();
    /** 通道 ID -> 该通道承载的设备 ID 集合（用于断线精准清理） */
    private final ConcurrentHashMap<String, Set<String>> channelDevices = new ConcurrentHashMap<>();

    /** 指令仓储：id -> 指令；history 为有序双端队列（新在前） */
    private final ConcurrentHashMap<String, DeviceCommand> repo = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<DeviceCommand> history = new ConcurrentLinkedDeque<>();
    private final AtomicLong seq = new AtomicLong();

    public CommandService(DeviceShadowService shadow, WebSocketPushService push) {
        this.shadow = shadow;
        this.push = push;
    }

    // ---------------------------------------------------------------------
    // 路由表维护（由 IngestGateway 在遥测/断线时调用）
    // ---------------------------------------------------------------------

    /** 绑定设备与通道：每次遥测都调用，幂等 */
    public void bind(String deviceId, Channel ch) {
        String cid = ch.id().asLongText();
        deviceChannels.computeIfAbsent(deviceId, k -> ConcurrentHashMap.newKeySet()).add(ch);
        channelDevices.computeIfAbsent(cid, k -> ConcurrentHashMap.newKeySet()).add(deviceId);
    }

    /** 通道断开：从所有设备路由集合中移除该通道 */
    public void unbind(Channel ch) {
        String cid = ch.id().asLongText();
        Set<String> devices = channelDevices.remove(cid);
        if (devices == null) {
            return;
        }
        for (String deviceId : devices) {
            Set<Channel> set = deviceChannels.get(deviceId);
            if (set != null) {
                set.remove(ch);
                if (set.isEmpty()) {
                    deviceChannels.remove(deviceId);
                }
            }
        }
    }

    /** 当前在线（可下发）设备数 */
    public int routableDeviceCount() {
        return deviceChannels.size();
    }

    // ---------------------------------------------------------------------
    // 指令下发
    // ---------------------------------------------------------------------

    public DeviceCommand sendCommand(String deviceId, String action, Map<String, Object> params) {
        DeviceCommand cmd = DeviceCommand.create(deviceId, action, params);
        String id = "CMD" + Long.toString(System.currentTimeMillis(), 36).toUpperCase()
                + "-" + Long.toString(seq.incrementAndGet(), 36).toUpperCase()
                + "-" + Integer.toString(rnd.nextInt(0x10000), 36).toUpperCase();
        cmd.setId(id);
        repo.put(id, cmd);
        history.addFirst(cmd);
        if (history.size() > 1000) {
            DeviceCommand removed = history.pollLast();
            if (removed != null) {
                repo.remove(removed.getId());
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cmdId", id);
        payload.put("deviceId", deviceId);
        payload.put("action", action);
        if (params != null) {
            payload.put("params", params);
        }
        byte[] bytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
        String topic = String.format(Constants.TOPIC_COMMAND, deviceId);
        int delivered = publishToDevice(deviceId, topic, bytes);
        if (delivered > 0) {
            cmd.setStatus("SENT");
            cmd.setSentAt(System.currentTimeMillis());
        } else {
            cmd.setStatus("NO_SESSION");
            cmd.setMessage("设备当前无活跃连接，指令无法下发");
        }
        push.pushCommand(cmd);
        log.info("下发指令 {} -> {} action={} 通道数={}", id, deviceId, action, delivered);
        return cmd;
    }

    /** 向指定设备的所有活跃通道下发 PUBLISH（QoS0），返回成功写入的通道数 */
    private int publishToDevice(String deviceId, String topic, byte[] payload) {
        Set<Channel> chs = deviceChannels.get(deviceId);
        if (chs == null || chs.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Channel ch : chs) {
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(new MqttMessage.Publish(0, topic, 0, payload));
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------------
    // 设备回执闭环
    // ---------------------------------------------------------------------

    public void onResponse(String deviceId, byte[] payload) {
        try {
            String json = new String(payload, StandardCharsets.UTF_8);
            Map<String, Object> map = JsonUtil.fromJson(json, Map.class);
            String cmdId = map.get("cmdId") == null ? null : String.valueOf(map.get("cmdId"));
            DeviceCommand cmd = cmdId != null ? repo.get(cmdId) : null;
            String status = map.getOrDefault("status", "OK").toString();
            @SuppressWarnings("unchecked")
            Map<String, Object> state = map.get("state") instanceof Map
                    ? (Map<String, Object>) map.get("state") : null;
            if (cmd == null) {
                log.warn("收到未知指令回执 device={} cmdId={}", deviceId, cmdId);
                return;
            }
            if ("OK".equals(status)) {
                cmd.setStatus("EXECUTED");
                if (state != null) {
                    cmd.setResult(new LinkedHashMap<>(state));
                    // 将设备执行后的控制状态同步到影子，前端面板即可读取
                    for (Map.Entry<String, Object> e : state.entrySet()) {
                        shadow.setControl(deviceId, e.getKey(), e.getValue());
                    }
                }
            } else {
                cmd.setStatus("FAILED");
                cmd.setMessage(map.get("message") == null ? "设备执行失败" : String.valueOf(map.get("message")));
            }
            cmd.setAckedAt(System.currentTimeMillis());
            push.pushCommand(cmd);
            log.info("指令回执 {} -> {} status={}", cmdId, deviceId, cmd.getStatus());
        } catch (Exception e) {
            log.warn("指令回执解析失败 device={}: {}", deviceId, e.getMessage());
        }
    }

    @Scheduled(fixedRate = 5000)
    public void sweepTimeout() {
        long now = System.currentTimeMillis();
        for (DeviceCommand cmd : history) {
            if ("SENT".equals(cmd.getStatus()) && cmd.getSentAt() != null
                    && now - cmd.getSentAt() > RESPONSE_TIMEOUT_MS) {
                cmd.setStatus("TIMEOUT");
                cmd.setMessage("设备未在 " + (RESPONSE_TIMEOUT_MS / 1000) + " 秒内回执，视为超时");
                push.pushCommand(cmd);
                log.warn("指令超时 {} -> {}", cmd.getId(), cmd.getDeviceId());
            }
        }
    }

    // ---------------------------------------------------------------------
    // 查询
    // ---------------------------------------------------------------------

    public List<DeviceCommand> recent(int limit, String deviceId) {
        List<DeviceCommand> out = new ArrayList<>();
        for (DeviceCommand c : history) {
            if (deviceId != null && !deviceId.equals(c.getDeviceId())) {
                continue;
            }
            out.add(c);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    public DeviceCommand get(String id) {
        return repo.get(id);
    }

    public List<String> controlNames() {
        return CONTROL_NAMES;
    }
}
