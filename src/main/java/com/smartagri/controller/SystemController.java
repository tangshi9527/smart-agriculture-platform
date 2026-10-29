package com.smartagri.controller;

import com.smartagri.alert.AlertRecordRepository;
import com.smartagri.device.DeviceShadowService;
import com.smartagri.influx.InfluxService;
import com.smartagri.mqtt.session.DeviceSessionRegistry;
import com.smartagri.common.Result;
import com.smartagri.simulator.SimulatorManager;
import com.smartagri.websocket.WebSocketPushService;
import com.smartagri.gateway.ApiVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统健康与状态汇总：前端据此展示存储（InfluxDB/Redis）在线/降级、MQTT、模拟器状态与推送吞吐。
 */
@RestController
@RequestMapping("/api/system")
@ApiVersion(1)
public class SystemController {

    private final InfluxService influx;
    private final DeviceShadowService shadow;
    private final DeviceSessionRegistry registry;
    private final SimulatorManager sim;
    private final AlertRecordRepository alertRepo;
    private final WebSocketPushService push;
    private final com.smartagri.mq.TelemetryMessageBus bus;
    private final com.smartagri.capacity.ResourceGuard resources;
    private final com.smartagri.ingest.IngestGateway ingest;

    @Value("${mqtt.port:1883}")
    private int mqttPort;

    public SystemController(InfluxService influx, DeviceShadowService shadow,
                           DeviceSessionRegistry registry, SimulatorManager sim,
                           AlertRecordRepository alertRepo, WebSocketPushService push,
                           com.smartagri.mq.TelemetryMessageBus bus,
                           com.smartagri.capacity.ResourceGuard resources,
                           com.smartagri.ingest.IngestGateway ingest) {
        this.influx = influx;
        this.shadow = shadow;
        this.registry = registry;
        this.sim = sim;
        this.alertRepo = alertRepo;
        this.push = push;
        this.bus = bus;
        this.resources = resources;
        this.ingest = ingest;
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> influxInfo = new LinkedHashMap<>();
        influxInfo.put("healthy", influx.isInfluxAvailable());
        influxInfo.put("mode", influx.isInfluxAvailable() ? "real" : "memory");
        // 降级窗口：这段区间内的历史数据可能不完整（降级期间的点只在内存、且有界淘汰）
        influxInfo.put("degradedSince", influx.degradedSince());
        influxInfo.put("degradedGaps", influx.degradedGaps());
        // why 决定运维看不看这件事：influx-down 要救火，cpu-pressure 是演示态正常
        influxInfo.put("why", influx.degradeReason());
        influxInfo.put("autoRecoverAt", influx.nextProbeAt());
        influxInfo.put("backoffAttempts", influx.recoverAttempts());
        // 落库通道的四种"丢"分开给：队列满丢（写入跟不上）/ 服务端拒绝（数据有问题）/
        // 非法字段剔除（传感器坏了）/ 内存淘汰（降级太久）。合成一个数会让运维照错方向查。
        influxInfo.put("write", influx.writeStats());
        // 降采样任务健康：retention 把 raw 压到 30 天之后，"task 停摆"就成了一条静默丢数据的路径，
        // 必须单独给结论（healthy / lastTaskSuccessAt / retentionRescued / gaps）
        influxInfo.put("downsample", influx.downsampleHealth());
        m.put("influx", influxInfo);

        Map<String, Object> redisInfo = new LinkedHashMap<>();
        redisInfo.put("healthy", shadow.isRedisAvailable());
        redisInfo.put("mode", shadow.isRedisAvailable() ? "real" : "memory");
        redisInfo.put("autoRecoverAt", shadow.nextProbeAt());
        redisInfo.put("backoffAttempts", shadow.recoverAttempts());
        m.put("redis", redisInfo);

        // 资源读数与「节流建议」：降级的第二道门槛，也是"该降采样率而不是切内存"的依据
        m.put("resources", resources.snapshot());
        // 脏数据三类分开：字段级剔除（数据变稀疏）≠ 整帧丢弃（数据缺失）≠ JSON 解析失败
        m.put("ingest", ingest.sanitizeStats());
        m.put("mqtt", Map.of("running", true, "port", mqttPort));
        m.put("connections", registry.connectionCount());
        m.put("devices", shadow.onlineCount());
        m.put("dashboards", push.onlineDashboards());
        m.put("alertsTotal", alertRepo.count());
        m.put("throughput", Map.of("telemetryPerSecond", push.telemetryPerSecond(),
                "telemetryTotal", push.telemetryTotal(),
                "alertTotal", push.alertTotal()));
        // 总线可观测：mode=local 时按链路拆分丢弃数，避免「只知道丢了多少、不知道谁在丢」
        Map<String, Object> busInfo = new LinkedHashMap<>();
        busInfo.put("mode", bus.mode());
        busInfo.put("droppedTotal", bus.droppedCount());
        if (bus instanceof com.smartagri.mq.local.LocalTelemetryBus local) {
            busInfo.put("droppedByConsumer", local.droppedByConsumer());
            busInfo.put("queueSizes", local.queueSizes());
        }
        m.put("bus", busInfo);
        m.put("simulator", sim.status());
        return Result.ok(m);
    }
}
