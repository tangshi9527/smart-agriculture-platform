package com.smartagri.alert;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 持久化告警记录：告警事件落库后可供告警中心查询、确认(ACK)、忽略(IGNORE)。
 */
@Entity
@Table(name = "t_alert_record")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ruleId;
    private String ruleName;
    private String deviceId;
    private String metric;
    @Column(name = "metric_value")
    private Double value;
    private Double threshold;
    private String operator;
    private String severity;
    private String message;
    private Long ts;

    /** ACTIVE / ACKED / IGNORED / RESOLVED */
    private String status;
}
