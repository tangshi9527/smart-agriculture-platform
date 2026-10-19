package com.smartagri.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 客户端测试：用 JDK 自带 HttpServer 起一个假网关，
 * 验证 SSE 增量解析与「非流式兜底」两条路径——这两段是手写协议解析，最容易出错。
 */
class LlmClientStreamTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesSseDeltasAndConcatenates() throws Exception {
        startServer("text/event-stream; charset=utf-8", """
                data: {"choices":[{"delta":{"content":"土壤"}}]}

                data: {"choices":[{"delta":{"content":"湿度"}}]}

                data: {"choices":[{"delta":{"content":"偏低。"}}]}

                data: [DONE]

                """);

        LlmClient client = newClient("sk-test");
        List<String> deltas = new ArrayList<>();
        LlmClient.StreamResult r = client.streamChat(
                List.of(ChatMessage.user("hi")), deltas::add);

        assertEquals(List.of("土壤", "湿度", "偏低。"), deltas);
        assertTrue(r.streamed());
        assertEquals(7, r.contentLength());
    }

    @Test
    void fallsBackToWholeJsonWhenGatewayIgnoresStream() throws Exception {
        startServer("application/json; charset=utf-8",
                "{\"choices\":[{\"message\":{\"content\":\"建议先开风机。\"}}]}");

        LlmClient client = newClient("sk-test");
        StringBuilder sb = new StringBuilder();
        LlmClient.StreamResult r = client.streamChat(
                List.of(ChatMessage.user("hi")), sb::append);

        assertEquals("建议先开风机。", sb.toString());
        assertEquals(false, r.streamed());
    }

    @Test
    void ignoresHeartbeatCommentsAndBrokenChunks() throws Exception {
        startServer("text/event-stream",
                ": keep-alive\n\ndata: {\"choices\":[]}\n\ndata: not-json\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n");

        LlmClient client = newClient("sk-test");
        StringBuilder sb = new StringBuilder();
        client.streamChat(List.of(ChatMessage.user("hi")), sb::append);

        assertEquals("OK", sb.toString());
    }

    @Test
    void throwsWhenNoApiKey() {
        AiProperties p = new AiProperties();
        p.setEnabled(true);
        p.setApiKey("");
        LlmClient client = new LlmClient(p);

        assertThrows(LlmException.class,
                () -> client.streamChat(List.of(ChatMessage.user("hi")), d -> { }));
    }

    /**
     * 网关不返回 [DONE]、却一直发数据（心跳/无限流）时，看门狗必须把线程收回来，
     * 否则 4 个 AI 线程会被慢慢占满，AI 功能整体不可用。
     */
    @Test
    void watchdogAbortsEndlessStream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0); // chunked
            try (OutputStream os = ex.getResponseBody()) {
                os.write("data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
                Thread.sleep(400);
                os.write("data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        AiProperties p = new AiProperties();
        p.setEnabled(true);
        p.setApiKey("sk-test");
        p.setBaseUrl("http://127.0.0.1:" + port() + "/v1");
        p.setStreamTimeoutMs(150); // 150ms 后必须放弃

        LlmClient client = new LlmClient(p);
        LlmException e = assertThrows(LlmException.class,
                () -> client.streamChat(List.of(ChatMessage.user("hi")), d -> { }));
        assertTrue(e.getMessage().contains("超时"), "应报流式超时，实际：" + e.getMessage());
    }

    @Test
    void throwsOnHttpError() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            byte[] body = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(429, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        LlmClient client = newClient("sk-test");
        LlmException e = assertThrows(LlmException.class,
                () -> client.streamChat(List.of(ChatMessage.user("hi")), d -> { }));
        assertTrue(e.getMessage().contains("429"));
    }

    // —— helpers ——

    private void startServer(String contentType, String payload) throws Exception {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    private LlmClient newClient(String key) {
        AiProperties p = new AiProperties();
        p.setEnabled(true);
        p.setApiKey(key);
        p.setBaseUrl("http://127.0.0.1:" + port() + "/v1");
        p.setModel("DeepSeek-V4-Flash");
        return new LlmClient(p);
    }

    private int port() {
        return server.getAddress().getPort();
    }
}
