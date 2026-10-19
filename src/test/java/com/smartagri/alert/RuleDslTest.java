package com.smartagri.alert;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleDslTest {

    @Test
    void parsesSingleRule() {
        String src = "RULE 高温预警\nWHEN temperature > 35\nTHEN CRITICAL \"高温\"\nEND";
        DslParseResult res = RuleDsl.parse(src);
        assertTrue(res.isOk(), () -> res.getErrors().toString());
        List<AlertRule> rules = res.getRules();
        assertEquals(1, rules.size());
        AlertRule r = rules.get(0);
        assertEquals("高温预警", r.getName());
        assertEquals("CRITICAL", r.getSeverity());
        assertEquals("高温", r.getMessage());
        assertEquals(1, r.getConditions().size());
        assertEquals("temperature", r.getConditions().get(0).getMetric());
        assertEquals("GT", r.getConditions().get(0).getOperator());
        assertEquals(35.0, r.getConditions().get(0).getThreshold());
    }

    @Test
    void parsesScopeAndAndLogicAndDuration() {
        String src = "RULE 温室异常\nSCOPE dev-01\nWHEN temperature > 35 AND humidity < 40\nHOLD FOR 8s\nTHEN WARN\nEND";
        DslParseResult res = RuleDsl.parse(src);
        assertTrue(res.isOk(), () -> res.getErrors().toString());
        AlertRule r = res.getRules().get(0);
        assertEquals("dev-01", r.getDeviceId());
        assertEquals("AND", r.getLogic());
        assertEquals(2, r.getConditions().size());
        assertEquals(8, r.getDurationSec());
        assertEquals("WARN", r.getSeverity());
    }

    @Test
    void rejectsMixedAndOr() {
        String src = "RULE bad\nWHEN a > 1 AND b < 2 OR c > 3\nEND";
        DslParseResult res = RuleDsl.parse(src);
        assertFalse(res.isOk());
        assertTrue(res.getErrors().stream()
                .anyMatch(e -> e.getMessage().contains("AND") && e.getMessage().contains("OR")));
    }

    @Test
    void rejectsMissingWhen() {
        String src = "RULE empty\nTHEN WARN\nEND";
        DslParseResult res = RuleDsl.parse(src);
        assertFalse(res.isOk());
    }

    @Test
    void rejectsBadCondition() {
        String src = "RULE bad\nWHEN temp 35\nEND";
        DslParseResult res = RuleDsl.parse(src);
        assertFalse(res.isOk());
    }
}
