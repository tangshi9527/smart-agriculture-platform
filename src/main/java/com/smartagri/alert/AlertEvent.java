package com.smartagri.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 触发产生的告警事件（运行时对象，存于内存环形缓冲）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertEvent {
    private String id;
    private String ruleId;
    private String ruleName;
    private String deviceId;
    private String metric;
    private double value;
    private double threshold;
    private String operator;
    private String severity;
    private String message;
    private long ts;
    private String status; // ACTIVE / ACKED / IGNORED / RESOLVED
}
