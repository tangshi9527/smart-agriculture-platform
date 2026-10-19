package com.smartagri.alert;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * DSL 解析结果：规则列表 + 错误列表。ok 表示无错误。
 */
@Data
public class DslParseResult {
    private boolean ok;
    private List<AlertRule> rules = new ArrayList<>();
    private List<DslError> errors = new ArrayList<>();
}
