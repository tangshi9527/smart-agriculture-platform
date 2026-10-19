package com.smartagri.alert;

import com.smartagri.device.Telemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertEngineTest {

    @Mock
    AlertRuleRepository ruleRepository;
    @Mock
    AlertRecordRepository recordRepository;

    private AlertRule highTempRule() {
        AlertRule r = new AlertRule();
        r.setId(1L);
        r.setName("高温");
        r.setSeverity("CRITICAL");
        r.setEnabled(true);
        r.setDeviceId("");
        r.setLogic("AND");
        r.setMetric("temperature");
        r.setOperator("GT");
        r.setThreshold(35.0);
        r.setConditions(List.of(new AlertCondition("temperature", "GT", 35.0)));
        return r;
    }

    private Telemetry telemetry(double temp) {
        return Telemetry.builder()
                .deviceId("dev-01")
                .metrics(Map.of("temperature", temp))
                .build();
    }

    @Test
    void firesWhenThresholdExceeded() {
        when(ruleRepository.findAll()).thenReturn(List.of(highTempRule()));
        AlertEngine engine = new AlertEngine(ruleRepository, recordRepository);
        engine.loadRules();

        List<AlertEvent> fired = engine.evaluate(telemetry(40.0));
        assertEquals(1, fired.size());
        assertEquals("CRITICAL", fired.get(0).getSeverity());
        verify(recordRepository, timeout(2000)).save(any(AlertRecord.class));
    }

    @Test
    void noFireWhenBelowThreshold() {
        when(ruleRepository.findAll()).thenReturn(List.of(highTempRule()));
        AlertEngine engine = new AlertEngine(ruleRepository, recordRepository);
        engine.loadRules();

        List<AlertEvent> fired = engine.evaluate(telemetry(20.0));
        assertTrue(fired.isEmpty());
        verify(recordRepository, never()).save(any());
    }

    @Test
    void deduplicatesActiveAlert() {
        when(ruleRepository.findAll()).thenReturn(List.of(highTempRule()));
        AlertEngine engine = new AlertEngine(ruleRepository, recordRepository);
        engine.loadRules();

        engine.evaluate(telemetry(40.0));
        List<AlertEvent> second = engine.evaluate(telemetry(40.0));
        assertEquals(0, second.size()); // 已激活 -> 去重，不再触发
    }

    @Test
    void clearsActiveWhenConditionRecovers() {
        when(ruleRepository.findAll()).thenReturn(List.of(highTempRule()));
        AlertEngine engine = new AlertEngine(ruleRepository, recordRepository);
        engine.loadRules();

        engine.evaluate(telemetry(40.0));   // 触发
        engine.evaluate(telemetry(20.0));   // 恢复，清除活动态
        List<AlertEvent> again = engine.evaluate(telemetry(40.0));
        assertEquals(1, again.size());      // 恢复后再次触发
    }
}
