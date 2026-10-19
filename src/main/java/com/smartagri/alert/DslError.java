package com.smartagri.alert;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * DSL 解析错误：携带行号（0 表示全局/非特定行）与可读描述。
 */
@Data
@AllArgsConstructor
public class DslError {
    /** 出错行号，0 表示全局或非特定行。 */
    private int line;
    /** 错误描述。 */
    private String message;
}
