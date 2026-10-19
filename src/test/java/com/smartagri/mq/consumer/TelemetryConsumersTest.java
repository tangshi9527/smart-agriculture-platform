package com.smartagri.mq.consumer;

import com.smartagri.ai.AiProperties;
import com.smartagri.ai.AlertExplainer;
import com.smartagri.ai.ExplainRequest;
import com.smartagri.alert.AlertEngine;
import com.smartagri.alert.AlertEvent;
import com.smartagri.device.Telemetry;
import com.smartagri.influx.InfluxService;
import com.smartagri.mq.TelemetryEvent;
import com.smartagri.websocket.WebSocketPushService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三个下游消费者单元测试：验证各自正确调用对应的下游服务（不依赖消息中间件）。
 * 直接构造消费者并注入 mock 依赖，避免 @InjectMocks 在构造器注入场景下的不确定性。
 */
@ExtendWith(MockitoExtension.class)
class TelemetryConsumersTest {

    @Mock InfluxService influx;
    @Mock AlertEngine alertEngine;
    @Mock WebSocketPushService push;
    @Mock AlertExplainer explainer;

    @Test
    void storageConsumerWritesToInflux() {
        StorageTelemetryConsumer storage = new StorageTelemetryConsumer(influx);
        storage.consume(event());

        ArgumentCaptor<Telemetry> captor = ArgumentCaptor.forClass(Telemetry.class);
        verify(influx).write(captor.capture());
        assertEquals("d1", captor.getValue().getDeviceId());
        assertEquals(25.5, captor.getValue().getMetrics().get("temp"));
    }

    @Test
    void alertConsumerEvaluatesAndPushesAlerts() {
        AlertEvent ae = AlertEvent.builder().id("A1").deviceId("d1").build();
        when(alertEngine.evaluate(any(Telemetry.class))).thenReturn(List.of(ae));

        AlertTelemetryConsumer alert = new AlertTelemetryConsumer(alertEngine, push, explainer, aiProps());
        alert.consume(event());

        verify(alertEngine).evaluate(any(Telemetry.class));
        verify(push).pushAlert(ae);
    }

    /** CRITICAL 告警应投递 AI 解读，且 cid 复用告警事件 ID，便于前端对齐 */
    @Test
    void criticalAlertTriggersAiExplain() {
        AlertEvent ae = AlertEvent.builder().id("A2").deviceId("d1")
                .severity("CRITICAL").build();
        when(alertEngine.evaluate(any(Telemetry.class))).thenReturn(List.of(ae));

        AlertTelemetryConsumer alert = new AlertTelemetryConsumer(alertEngine, push, explainer, aiProps());
        alert.consume(event());

        ArgumentCaptor<ExplainRequest> captor = ArgumentCaptor.forClass(ExplainRequest.class);
        verify(explainer).submit(captor.capture(), eq(true));
        assertEquals("A2", captor.getValue().getCid());
        assertEquals("d1", captor.getValue().getDeviceId());
    }

    /** 低于门槛级别的告警不解读，避免无谓的 token 消耗 */
    @Test
    void warnAlertSkipsAiExplain() {
        AlertEvent ae = AlertEvent.builder().id("A3").deviceId("d1")
                .severity("WARN").build();
        when(alertEngine.evaluate(any(Telemetry.class))).thenReturn(List.of(ae));

        AlertTelemetryConsumer alert = new AlertTelemetryConsumer(alertEngine, push, explainer, aiProps());
        alert.consume(event());

        verify(explainer, never()).submit(any(), eq(true));
    }

    /** 级别比较工具本身的边界 */
    @Test
    void severityRankingIsMonotonic() {
        assertTrue(AlertExplainer.severityAtLeast("CRITICAL", "WARN"));
        assertTrue(AlertExplainer.severityAtLeast("WARN", "WARN"));
        assertFalse(AlertExplainer.severityAtLeast("INFO", "WARN"));
        assertFalse(AlertExplainer.severityAtLeast(null, "INFO"));
    }

    @Test
    void pushConsumerPushesTelemetry() {
        PushTelemetryConsumer pushConsumer = new PushTelemetryConsumer(push);
        pushConsumer.consume(event());

        verify(push).pushTelemetry(any(Telemetry.class));
    }

    @Test
    void alertConsumerWithNoFiredAlertsPushesNothing() {
        when(alertEngine.evaluate(any(Telemetry.class))).thenReturn(Collections.emptyList());

        AlertTelemetryConsumer alert = new AlertTelemetryConsumer(alertEngine, push, explainer, aiProps());
        alert.consume(event());

        verify(alertEngine).evaluate(any(Telemetry.class));
        verify(push, never()).pushAlert(any());
    }

    private AiProperties aiProps() {
        AiProperties p = new AiProperties();
        p.getExplain().setAuto(true);
        p.getExplain().setMinSeverity("CRITICAL");
        return p;
    }

    private TelemetryEvent event() {
        return TelemetryEvent.builder()
                .eventId("e1").deviceId("d1").ts(1L).producedAt(2L)
                .metrics(Map.of("temp", 25.5)).build();
    }
}
