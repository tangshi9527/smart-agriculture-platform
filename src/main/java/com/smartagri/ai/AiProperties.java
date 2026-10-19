package com.smartagri.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大模型接入配置（前缀 llm.*）。
 * 默认指向联通云 AISP 的 OpenAI 兼容网关；换供应商只需改 base-url + model，代码不动。
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class AiProperties {

    /** 总开关。false 时所有 AI 接口直接走本地模板解读，不发外部请求。 */
    private boolean enabled = false;

    /** OpenAI 兼容 base_url（末尾不要带 /） */
    private String baseUrl = "https://aigw-gzgy2.cucloud.cn:8443/v1";

    /** API Key，形如 sk-xxx。留空则视为未配置。 */
    private String apiKey = "";

    /** 模型名，需与网关文档一致 */
    private String model = "DeepSeek-V4-Flash";

    /** 单次解读最大生成 token 数 */
    private int maxTokens = 512;

    private double temperature = 0.3;

    /** 建连超时（毫秒） */
    private long connectTimeoutMs = 5000;

    /**
     * 流式响应总时长上限（毫秒）。
     * 网关不返回 [DONE] 却持续发心跳时，靠这个把线程收回来；超时抛异常 → 交给熔断/降级。
     */
    private long streamTimeoutMs = 60000;

    /** 告警自动解读策略 */
    private Explain explain = new Explain();

    @Data
    public static class Explain {
        /** 是否在告警触发时自动解读 */
        private boolean auto = true;

        /** 触发自动解读的最低级别：INFO / WARN / CRITICAL */
        private String minSeverity = "CRITICAL";

        /** 同设备同规则的最小解读间隔（秒），防告警风暴烧 token */
        private int throttleSeconds = 60;

        /** 是否允许把解读结果写入数据库 */
        private boolean persist = true;
    }

    /** key 是否已配置 */
    public boolean configured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
