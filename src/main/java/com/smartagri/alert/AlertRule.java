package com.smartagri.alert;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 告警规则（动态加载，无需重启服务即可生效）。
 * 支持：
 *  - 多条件组合：conditions 列表，按 logic(AND/OR) 组合求值
 *  - 持续时长：durationSec>0 时，条件需持续满足该秒数才触发告警
 *  - 兼容旧单条件：metric/operator/threshold 作为首条件的镜像，便于下游展示
 * operator: GT / LT / GTE / LTE / EQ
 * deviceId 为空表示作用于全部设备。
 */
@Data
@Entity
@Table(name = "t_alert_rule")
public class AlertRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String name;

    @Column(length = 64)
    private String deviceId; // 空=全部

    // —— 旧单条件字段（作为首条件镜像，向下兼容）——
    @Column(nullable = false, length = 32)
    private String metric;

    @Column(nullable = false, length = 8)
    private String operator = "GT";

    @Column(nullable = false)
    private double threshold;

    // —— 多条件增强 ——
    @Column(length = 8)
    private String logic = "AND"; // 多条件组合方式: AND / OR

    @Column
    private int durationSec = 0; // 持续时长(秒)，0=即时触发

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "t_alert_rule_cond", joinColumns = @JoinColumn(name = "rule_id"))
    @OrderColumn(name = "cond_order")
    private List<AlertCondition> conditions = new ArrayList<>();

    @Column(length = 16)
    private String severity = "WARN"; // INFO / WARN / CRITICAL

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 256)
    private String message;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** 实际参与求值的条件：优先用 conditions，回退到旧单条件字段。 */
    public List<AlertCondition> effectiveConditions() {
        if (conditions != null && !conditions.isEmpty()) {
            return conditions;
        }
        if (metric != null && !metric.isEmpty()) {
            return List.of(new AlertCondition(metric, operator, threshold));
        }
        return List.of();
    }
}
