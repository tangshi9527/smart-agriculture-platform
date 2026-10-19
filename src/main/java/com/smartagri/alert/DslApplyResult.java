package com.smartagri.alert;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * DSL 应用结果：是否全部成功、实际写入条数、解析出的规则、错误列表。
 */
@Data
@AllArgsConstructor
public class DslApplyResult {
    private boolean ok;
    private int applied;
    private List<AlertRule> rules;
    private List<DslError> errors;
}
