package com.smartagri.mq;

import com.smartagri.device.Telemetry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 遥测事件：设备上报经接入层封装后的领域事件，是消息队列的解耦边界。
 * 接入网关注入此事件到消息总线，下游「持久化 / 告警 / 实时推送」三个消费者各自独立消费，
 * 彼此隔离——任一消费者抖动或故障都不会阻塞设备接入与另外两条处理链路。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryEvent {

    /** 事件唯一 ID（可用于幂等 / 链路追踪） */
    private String eventId;
    /** 设备 ID */
    private String deviceId;
    /** 采样时间戳（epoch millis） */
    private long ts;
    /** 各传感器读数（温度 / 湿度 / 土壤湿度 / 光照 / CO2 / pH 等） */
    private Map<String, Double> metrics;
    /** 事件产生时间（由接入层在发布时填入） */
    private long producedAt;

    /** 由接入层从设备遥测封装为事件 */
    public static TelemetryEvent from(Telemetry t) {
        return TelemetryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .deviceId(t.getDeviceId())
                .ts(t.getTs())
                .metrics(t.getMetrics() == null ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(t.getMetrics()))
                .producedAt(System.currentTimeMillis())
                .build();
    }

    /** 还原为下游服务所需的 Telemetry 对象 */
    public Telemetry toTelemetry() {
        Telemetry t = Telemetry.builder()
                .deviceId(deviceId)
                .ts(ts)
                .build();
        if (metrics != null) {
            metrics.forEach(t::putMetric);
        }
        return t;
    }
}
