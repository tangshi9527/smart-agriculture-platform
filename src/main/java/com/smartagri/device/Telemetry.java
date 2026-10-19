package com.smartagri.device;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备遥测数据点。metrics 为各传感器读数（温度/湿度/土壤湿度/光照/CO2/pH 等）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Telemetry {
    private String deviceId;
    private long ts; // epoch millis
    @Builder.Default
    private Map<String, Double> metrics = new LinkedHashMap<>();

    public void putMetric(String name, double value) {
        metrics.put(name, value);
    }
}
