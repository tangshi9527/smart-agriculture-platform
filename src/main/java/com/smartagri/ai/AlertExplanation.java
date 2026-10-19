package com.smartagri.ai;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 告警解读结果。以 cid 唯一，一条告警最多一份解读（重复请求会覆盖）。
 */
@Entity
@Table(name = "t_ai_explanation", indexes = @Index(name = "idx_ai_cid", columnList = "cid"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertExplanation {

    /** 解读成功 */
    public static final String STATUS_DONE = "DONE";
    /** 用本地规则模板生成（未调用大模型） */
    public static final String STATUS_LOCAL = "LOCAL";
    /** 调用失败 */
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String cid;

    private String deviceId;
    private String ruleName;
    private String metric;
    private String severity;

    /** 实际使用的模型；降级时为 local-template */
    private String model;

    /** DONE / LOCAL / FAILED */
    private String status;

    @Column(length = 2000)
    private String content;

    @Column(length = 500)
    private String error;

    /** 生成耗时（毫秒） */
    private Long latencyMs;

    /** 告警触发时间 */
    private Long alertTs;

    private Long createdAt;
}
