package com.smartagri.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发起一次告警解读的请求。cid 可由前端生成，用于把流式结果和这条告警对上号。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplainRequest {

    /** 关联 ID；为空时由服务端生成 */
    private String cid;

    private String deviceId;
    private String ruleName;
    private String metric;
    private Double value;
    private Double threshold;
    private String operator;
    private String severity;
    private String message;
    private Long ts;
}
