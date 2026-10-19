package com.smartagri.ai;

import com.smartagri.websocket.WebSocketPushService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 本地降级模板测试：没配 Key / 熔断打开 / 调用失败时都会走这里，
 * 必须保证四段结构完整，前端才能稳定渲染。
 */
class AlertExplainerFallbackTest {

    private AlertExplainer explainer() {
        AiProperties props = new AiProperties();
        props.setEnabled(false);
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.initialize();
        return new AlertExplainer(
                props,
                new LlmClient(props),
                mock(WebSocketPushService.class),
                mock(AlertExplanationRepository.class),
                CircuitBreakerRegistry.ofDefaults(),
                ex);
    }

    @Test
    void temperatureAlertProducesFourSections() {
        String text = explainer().localExplain(ExplainRequest.builder()
                .deviceId("dev-1").ruleName("高温告警").metric("temperature")
                .value(38.0).threshold(35.0).operator(">").severity("CRITICAL").build());

        assertTrue(text.contains("【可能原因】"));
        assertTrue(text.contains("【影响】"));
        assertTrue(text.contains("【处置建议】"));
        assertTrue(text.contains("【是否需要人工】"));
        assertTrue(text.contains("需要"), "CRITICAL 应判定为需要人工");
    }

    @Test
    void soilMoistureGivesIrrigationAdvice() {
        String text = explainer().localExplain(ExplainRequest.builder()
                .deviceId("dev-1").metric("soilMoisture").severity("WARN")
                .value(12.0).threshold(25.0).operator("<").build());

        assertTrue(text.contains("灌溉"));
        assertTrue(text.contains("不需要"), "WARN 不应要求人工介入");
    }

    @Test
    void unknownMetricFallsBackToGenericAdvice() {
        String text = explainer().localExplain(ExplainRequest.builder()
                .deviceId("dev-9").metric("windSpeed").severity("INFO").build());

        assertTrue(text.contains("【可能原因】"));
        assertTrue(text.contains("传感器"), "未知指标应引导先排查传感器");
    }
}
