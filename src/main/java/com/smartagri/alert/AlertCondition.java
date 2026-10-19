package com.smartagri.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 告警规则的单个条件（嵌入对象，随规则一起持久化）。
 * operator: GT / LT / GTE / LTE / EQ
 */
@Data
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class AlertCondition {

    @Column(length = 32)
    private String metric;

    @Column(length = 8)
    private String operator = "GT";

    @Column
    private double threshold;
}
