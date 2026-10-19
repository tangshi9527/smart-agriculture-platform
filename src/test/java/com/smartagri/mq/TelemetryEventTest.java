package com.smartagri.mq;

import com.smartagri.common.JsonUtil;
import com.smartagri.device.Telemetry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 遥测事件模型测试：from/toTelemetry 往返，以及 JSON 序列化往返。
 */
class TelemetryEventTest {

    @Test
    void fromAndToTelemetryRoundTrip() {
        Telemetry t = new Telemetry();
        t.setDeviceId("d1");
        t.setTs(123L);
        t.putMetric("temp", 25.5);
        t.putMetric("humidity", 60.0);

        TelemetryEvent event = TelemetryEvent.from(t);
        assertNotNull(event.getEventId());
        assertEquals("d1", event.getDeviceId());
        assertEquals(123L, event.getTs());

        Telemetry back = event.toTelemetry();
        assertEquals("d1", back.getDeviceId());
        assertEquals(25.5, back.getMetrics().get("temp"));
        assertEquals(60.0, back.getMetrics().get("humidity"));
    }

    @Test
    void jsonRoundTrip() {
        TelemetryEvent event = TelemetryEvent.builder()
                .eventId("e1").deviceId("d1").ts(1L).producedAt(2L)
                .metrics(Map.of("h", 1.0)).build();

        String json = JsonUtil.toJson(event);
        TelemetryEvent back = JsonUtil.fromJson(json, TelemetryEvent.class);

        assertEquals("e1", back.getEventId());
        assertEquals("d1", back.getDeviceId());
        assertEquals(1.0, back.getMetrics().get("h"));
    }
}
