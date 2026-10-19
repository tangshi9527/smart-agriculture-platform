package com.smartagri.ai;

import com.smartagri.common.Result;
import com.smartagri.gateway.ApiVersion;
import com.smartagri.gateway.ratelimit.KeyType;
import com.smartagri.gateway.ratelimit.RateLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 告警解读接口。
 *
 * <p>解读是异步的：POST 只返回 cid，正文通过 WebSocket 的 ai_explain_chunk 增量推送，
 * 结束后再发 ai_explain_done。这样既不占 HTTP 连接，也能直接复用大屏已有的长连接。</p>
 */
@RestController
@RequestMapping("/api/ai")
@ApiVersion(1)
public class AiController {

    private final AlertExplainer explainer;
    private final AiProperties props;
    private final AlertExplanationRepository repository;

    public AiController(AlertExplainer explainer, AiProperties props,
                        AlertExplanationRepository repository) {
        this.explainer = explainer;
        this.props = props;
        this.repository = repository;
    }

    /** 手动触发解读（用户点"AI 解读"按钮）。每人每分钟限 20 次，防止被当成免费 LLM 网关。 */
    @PostMapping("/explain")
    @RateLimit(capacity = 20, refillTokens = 20, refillSeconds = 60, keyType = KeyType.USER)
    public Result<Map<String, Object>> explain(@RequestBody ExplainRequest req) {
        String cid = explainer.submit(req, false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cid", cid);
        data.put("stream", "ws");
        data.put("model", props.configured() ? props.getModel() : "local-template");
        return Result.ok(data);
    }

    /** 查询历史解读结果（页面刷新后回填用） */
    @GetMapping("/explain/{cid}")
    public Result<AlertExplanation> get(@PathVariable("cid") String cid) {
        return repository.findByCid(cid)
                .map(Result::ok)
                .orElseGet(() -> Result.fail("未找到该解读记录"));
    }

    /** 能力状态：前端据此决定按钮是"AI 解读"还是"本地解读" */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.isEnabled());
        m.put("configured", props.configured());
        m.put("model", props.configured() ? props.getModel() : "local-template");
        m.put("baseUrl", maskUrl(props.getBaseUrl()));
        m.put("autoExplain", props.getExplain().isAuto());
        m.put("minSeverity", props.getExplain().getMinSeverity());
        m.put("throttleSeconds", props.getExplain().getThrottleSeconds());
        return Result.ok(m);
    }

    /** 只暴露主机名，不泄露 key 也不回显完整路径 */
    private static String maskUrl(String url) {
        if (url == null || url.isBlank()) {
            return "-";
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "-";
        }
    }
}
