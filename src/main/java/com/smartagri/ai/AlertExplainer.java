package com.smartagri.ai;

import com.smartagri.websocket.WebSocketPushService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * 告警智能解读编排：拼提示词 → 调大模型（流式）→ 边收边推 → 落库。
 *
 * <p>三条保命设计：</p>
 * <ol>
 *   <li><b>线程池隔离</b>：走 {@code llmExecutor}，队列满直接拒绝，绝不反压告警主链路；</li>
 *   <li><b>熔断降级</b>：resilience4j 的 {@code llm} 实例打开后不再发请求，直接给本地模板；</li>
 *   <li><b>节流</b>：同设备同规则在 {@code llm.explain.throttle-seconds} 内只解读一次，防告警风暴烧 token。</li>
 * </ol>
 */
@Slf4j
@Service
public class AlertExplainer {

    private static final String CB_NAME = "llm";
    private static final String LOCAL_MODEL = "local-template";
    private static final int MAX_THROTTLE_KEYS = 5000;

    private static final String SYSTEM_PROMPT = """
            你是农业物联网平台的运维助手。用户会给出一条刚触发的设备告警，请用简体中文做简明分析。
            输出必须严格是下面四行，每行以方括号标题开头，不要寒暄，不要复述输入，不要使用 Markdown：
            【可能原因】给出 1-2 条最可能的原因
            【影响】如果不处理会怎样
            【处置建议】给出 2-3 个可执行动作，按优先级排列
            【是否需要人工】只回答"需要"或"不需要"，再用一句话说明判断依据
            全文不超过 200 字。""";

    private final AiProperties props;
    private final LlmClient client;
    private final WebSocketPushService push;
    private final AlertExplanationRepository repository;
    private final CircuitBreaker circuitBreaker;
    private final ThreadPoolTaskExecutor executor;

    /** deviceId|ruleId → 上次解读时间戳 */
    private final Map<String, Long> lastExplainAt = new ConcurrentHashMap<>();

    public AlertExplainer(AiProperties props,
                          LlmClient client,
                          WebSocketPushService push,
                          AlertExplanationRepository repository,
                          CircuitBreakerRegistry cbRegistry,
                          @Qualifier(AiConfig.LLM_EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.props = props;
        this.client = client;
        this.push = push;
        this.repository = repository;
        this.circuitBreaker = cbRegistry.circuitBreaker(CB_NAME);
        this.executor = executor;
    }

    /**
     * 提交一次解读。立刻返回 cid，结果通过 WebSocket 的 ai_explain_* 消息送达。
     *
     * @param fromAuto 是否由告警自动触发（自动触发才走节流，手动点击不节流）
     */
    public String submit(ExplainRequest req, boolean fromAuto) {
        String cid = (req.getCid() == null || req.getCid().isBlank())
                ? UUID.randomUUID().toString()
                : req.getCid();
        req.setCid(cid);

        if (fromAuto && throttled(req)) {
            log.debug("AI 解读被节流: device={} rule={}", req.getDeviceId(), req.getRuleName());
            return cid;
        }

        try {
            executor.execute(() -> run(cid, req));
            // 真正投递成功才占用节流窗口：手动触发也刷新窗口，
            // 否则用户刚点完，紧随其后的自动解读还会再打一次模型
            markExplained(req);
        } catch (RejectedExecutionException e) {
            log.warn("AI 解读队列已满，本次跳过 cid={}", cid);
            push.pushAiExplain("done", payload(cid, req, Map.of(
                    "status", AlertExplanation.STATUS_FAILED,
                    "model", LOCAL_MODEL,
                    "content", "AI 解读排队已满（并发保护），稍后再试或直接查看原始告警。",
                    "latencyMs", 0)));
        }
        return cid;
    }

    /** 实际执行体 */
    private void run(String cid, ExplainRequest req) {
        long t0 = System.currentTimeMillis();
        push.pushAiExplain("start", payload(cid, req, Map.of(
                "model", props.configured() ? props.getModel() : LOCAL_MODEL)));

        StringBuilder acc = new StringBuilder();
        String status;
        String model;
        String error = null;

        if (!props.configured()) {
            String local = localExplain(req);
            acc.append(local);
            push.pushAiExplain("chunk", payload(cid, req, Map.of("delta", local)));
            status = AlertExplanation.STATUS_LOCAL;
            model = LOCAL_MODEL;
        } else {
            try {
                circuitBreaker.executeRunnable(() ->
                        client.streamChat(buildMessages(req),
                                delta -> {
                                    acc.append(delta);
                                    push.pushAiExplain("chunk", payload(cid, req, Map.of("delta", delta)));
                                }));
                status = AlertExplanation.STATUS_DONE;
                model = props.getModel();
            } catch (CallNotPermittedException e) {
                // 熔断打开：不发请求，直接本地模板，保证大屏不空白
                String local = localExplain(req);
                acc.setLength(0);
                acc.append(local);
                push.pushAiExplain("chunk", payload(cid, req, Map.of("delta", local)));
                status = AlertExplanation.STATUS_LOCAL;
                model = LOCAL_MODEL;
                error = "熔断已打开，降级为本地模板";
            } catch (Exception e) {
                String local = localExplain(req);
                acc.setLength(0);
                acc.append(local);
                push.pushAiExplain("chunk", payload(cid, req, Map.of("delta", local)));
                status = AlertExplanation.STATUS_LOCAL;
                model = LOCAL_MODEL;
                error = e.getMessage();
                log.warn("AI 解读失败，已降级本地模板 cid={}: {}", cid, e.getMessage());
            }
        }

        long latency = System.currentTimeMillis() - t0;
        String content = acc.toString();

        if (props.getExplain().isPersist()) {
            persist(cid, req, content, status, model, error, latency);
        }

        Map<String, Object> done = new LinkedHashMap<>();
        done.put("status", status);
        done.put("model", model);
        done.put("content", content);
        done.put("latencyMs", latency);
        if (error != null) {
            done.put("error", error);
        }
        push.pushAiExplain("done", payload(cid, req, done));
        log.info("AI 解读完成 cid={} device={} status={} latency={}ms", cid, req.getDeviceId(), status, latency);
    }

    /** 组装提示词 */
    private List<ChatMessage> buildMessages(ExplainRequest req) {
        StringBuilder u = new StringBuilder("设备：").append(nz(req.getDeviceId()));
        u.append("\n规则：").append(nz(req.getRuleName()));
        u.append("\n指标：").append(nz(req.getMetric()));
        u.append("\n当前值：").append(req.getValue());
        u.append("\n阈值：").append(nz(req.getOperator())).append(' ').append(req.getThreshold());
        u.append("\n级别：").append(nz(req.getSeverity()));
        if (req.getTs() != null) {
            u.append("\n触发时间：").append(java.time.Instant.ofEpochMilli(req.getTs()));
        }
        if (req.getMessage() != null) {
            u.append("\n原始描述：").append(req.getMessage());
        }
        return List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(u.toString()));
    }

    /**
     * 本地规则模板：没有 Key、熔断打开、调用失败时用。
     * 不是"假 AI"，是明确的规则兜底——前端会标 LOCAL，用户知道这不是模型生成。
     */
    String localExplain(ExplainRequest req) {
        String metric = nz(req.getMetric()).toLowerCase();
        String cause;
        String impact;
        String action;

        if (metric.contains("temp")) {
            cause = "棚内通风不足或遮阳未展开；也可能是传感器探头被阳光直射导致读数偏高";
            impact = "持续高温会造成叶片灼伤、花粉活性下降，直接影响坐果率";
            action = "先开风机与顶部通风口降温；展开遮阳网；用红外测温枪复核传感器附近实际温度";
        } else if (metric.contains("soil") || metric.contains("moist")) {
            cause = "灌溉未及时开启，或滴灌管路堵塞、水压不足";
            impact = "根系缺水会导致萎蔫，长时间缺水后恢复困难";
            action = "开启滴灌 10-15 分钟并观察回升曲线；检查滴头是否堵塞；确认水压是否正常";
        } else if (metric.contains("humid")) {
            cause = "密闭时间过长、通风不足，或夜间温度下降导致结露";
            impact = "高湿环境容易诱发灰霉病、霜霉病等真菌性病害";
            action = "增加通风时长；避免傍晚灌溉；必要时投放抑菌烟剂";
        } else if (metric.contains("co2")) {
            cause = "通风不良导致二氧化碳累积，或密闭棚内呼吸作用集中";
            impact = "浓度过高会影响人员安全，也会抑制部分作物代谢";
            action = "立即开启通风；人员进入前先换气并佩戴检测仪";
        } else if (metric.contains("light") || metric.contains("lux")) {
            cause = "遮阳网未按预期收起，或连续阴天光照不足";
            impact = "光照不足会拖慢光合作用，导致徒长与减产";
            action = "检查遮阳网控制回路；阴天启用补光灯；记录连续低光照天数";
        } else if (metric.contains("ph")) {
            cause = "灌溉水质波动或施肥后未充分混匀";
            impact = "pH 偏离适宜区间会影响养分吸收，出现缺素症状";
            action = "复测基质与灌溉液 pH；调整肥液配比；检查水源是否变化";
        } else {
            cause = "传感器读数异常或环境参数短时越界";
            impact = "若为真实越界，持续存在会影响作物生长；若为传感器漂移则会干扰后续判断";
            action = "先核对同棚其他设备读数是否一致；检查设备在线状态与供电；确认后按现场情况处置";
        }

        boolean needHuman = "CRITICAL".equalsIgnoreCase(nz(req.getSeverity()));
        return "【可能原因】" + cause + "\n"
                + "【影响】" + impact + "\n"
                + "【处置建议】" + action + "\n"
                + "【是否需要人工】" + (needHuman ? "需要，严重级别为 CRITICAL，建议现场确认后再恢复自动控制" : "不需要，可先按上述动作自动处置并观察 10 分钟");
    }

    private void persist(String cid, ExplainRequest req, String content, String status,
                         String model, String error, long latency) {
        try {
            AlertExplanation e = repository.findByCid(cid).orElseGet(AlertExplanation::new);
            e.setCid(cid);
            e.setDeviceId(req.getDeviceId());
            e.setRuleName(req.getRuleName());
            e.setMetric(req.getMetric());
            e.setSeverity(req.getSeverity());
            e.setModel(model);
            e.setStatus(status);
            e.setContent(truncate(content, 2000));
            e.setError(truncate(error, 500));
            e.setLatencyMs(latency);
            e.setAlertTs(req.getTs());
            e.setCreatedAt(System.currentTimeMillis());
            repository.save(e);
        } catch (Exception ex) {
            log.warn("AI 解读落库失败（不影响推送）cid={}: {}", cid, ex.getMessage());
        }
    }

    /** 只读判断，不改状态；窗口时间戳由 {@link #markExplained} 在投递成功后写入 */
    private boolean throttled(ExplainRequest req) {
        String key = nz(req.getDeviceId()) + "|" + nz(req.getRuleName());
        Long last = lastExplainAt.get(key);
        return last != null && System.currentTimeMillis() - last < Math.max(0, props.getExplain().getThrottleSeconds()) * 1000L;
    }

    private void markExplained(ExplainRequest req) {
        if (lastExplainAt.size() > MAX_THROTTLE_KEYS) {
            lastExplainAt.clear();
        }
        lastExplainAt.put(nz(req.getDeviceId()) + "|" + nz(req.getRuleName()), System.currentTimeMillis());
    }

    private Map<String, Object> payload(String cid, ExplainRequest req, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cid", cid);
        m.put("deviceId", req.getDeviceId());
        m.put("severity", req.getSeverity());
        m.putAll(extra);
        return m;
    }

    public static boolean severityAtLeast(String actual, String min) {
        return rank(actual) >= rank(min);
    }

    private static int rank(String s) {
        if (s == null) {
            return 0;
        }
        return switch (s.toUpperCase()) {
            case "CRITICAL" -> 3;
            case "WARN" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
