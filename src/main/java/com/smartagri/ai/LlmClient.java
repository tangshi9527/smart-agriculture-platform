package com.smartagri.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartagri.common.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * OpenAI 兼容的对话客户端（联通云 AISP / DeepSeek 等）。
 *
 * <p>只用 JDK 自带 HttpClient，不引任何 SDK：</p>
 * <ul>
 *   <li>优先请求 stream=true，逐行解析 SSE 的 {@code data: {...}} 增量，边收边回调；</li>
 *   <li>若网关不支持流式（返回的不是 text/event-stream），自动降级为整包解析；</li>
 *   <li>非 200 一律抛出 {@link LlmException}，由上层熔断/降级处理。</li>
 * </ul>
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final int ERR_BODY_LIMIT = 20;

    private final AiProperties props;
    private final HttpClient http;

    public LlmClient(AiProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    /**
     * 流式对话。每收到一段增量文本就回调一次 onDelta。
     *
     * @return 完整文本长度与是否走了流式，供上层做观测
     */
    public StreamResult streamChat(List<ChatMessage> messages, Consumer<String> onDelta) {
        if (!props.configured()) {
            throw new LlmException("未配置 llm.api-key，无法调用大模型");
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(props.getBaseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(messages), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<Stream<String>> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofLines());

            if (resp.statusCode() != 200) {
                String err;
                try (Stream<String> s = resp.body()) {
                    err = s.limit(ERR_BODY_LIMIT).collect(Collectors.joining("\n"));
                }
                throw new LlmException("大模型返回 HTTP " + resp.statusCode() + "：" + err);
            }

            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            try (Stream<String> lines = resp.body()) {
                if (contentType.contains("text/event-stream")) {
                    return parseSse(lines, onDelta);
                }
                return parseWhole(lines.collect(Collectors.joining("\n")), onDelta);
            }
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("调用被中断");
        } catch (Exception e) {
            throw new LlmException("调用失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // —— 内部实现 ——

    private String buildBody(List<ChatMessage> messages) {
        ObjectNode body = JsonUtil.mapper().createObjectNode();
        body.put("model", props.getModel());
        body.put("stream", true);
        body.put("temperature", props.getTemperature());
        if (props.getMaxTokens() > 0) {
            body.put("max_tokens", props.getMaxTokens());
        }
        ArrayNode arr = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode o = arr.addObject();
            o.put("role", m.role());
            o.put("content", m.content());
        }
        return body.toString();
    }

    /** 解析 SSE：只认 data: 行，忽略注释行与空行，遇 [DONE] 结束。 */
    private StreamResult parseSse(Stream<String> lines, Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
        int[] chunks = {0};
        long limit = props.getStreamTimeoutMs() > 0 ? props.getStreamTimeoutMs() : Long.MAX_VALUE / 2;
        long deadline = System.currentTimeMillis() + limit;
        lines.forEach(line -> {
            // 看门狗：网关不返回 [DONE] 却持续发心跳时，靠这里把线程收回来。
            // 注意：只能兜「有数据但没完」的情况；完全静默的读阻塞由底层 socket 超时兜，
            // JDK HttpClient 没有公开的读超时 API，这点在注释里说清楚，不假装能全覆盖。
            if (System.currentTimeMillis() > deadline) {
                throw new LlmException("流式响应超时（超过 " + props.getStreamTimeoutMs() + "ms 未结束）");
            }
            if (line == null || line.isEmpty() || line.startsWith(":")) {
                return;
            }
            String payload = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                return;
            }
            JsonNode node = readQuietly(payload);
            if (node == null) {
                return;
            }
            String delta = node.path("choices").path(0).path("delta").path("content").asText("");
            if (delta.isEmpty()) {
                // 部分网关流式里仍用 message 字段
                delta = node.path("choices").path(0).path("message").path("content").asText("");
            }
            if (!delta.isEmpty()) {
                full.append(delta);
                chunks[0]++;
                onDelta.accept(delta);
            }
        });
        if (chunks[0] == 0) {
            throw new LlmException("流式响应中没有可用内容");
        }
        return new StreamResult(full.length(), true);
    }

    /** 非流式兜底：整包 JSON 一次取出 message.content。 */
    private StreamResult parseWhole(String raw, Consumer<String> onDelta) {
        JsonNode node = readQuietly(raw);
        if (node == null) {
            throw new LlmException("响应不是合法 JSON");
        }
        String content = node.path("choices").path(0).path("message").path("content").asText("");
        if (content.isEmpty()) {
            throw new LlmException("响应中没有 choices[0].message.content");
        }
        onDelta.accept(content);
        return new StreamResult(content.length(), false);
    }

    private JsonNode readQuietly(String text) {
        try {
            return JsonUtil.mapper().readTree(text);
        } catch (Exception e) {
            log.debug("跳过无法解析的 SSE 片段: {}", text.length() > 80 ? text.substring(0, 80) : text);
            return null;
        }
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 调用结果摘要 */
    public record StreamResult(int contentLength, boolean streamed) {
    }
}
