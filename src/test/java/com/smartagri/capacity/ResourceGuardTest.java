package com.smartagri.capacity;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源守卫：它只负责"测"，判断交给调用方。
 *
 * <p>关键契约：CPU 读数不可用时不能永远判"不紧张"——否则在拿不到指标的平台上，
 * 降级的第二道门槛等于不存在。</p>
 */
class ResourceGuardTest {

    private ResourceGuard guard(double cpu, double heap) {
        ResourceGuard g = new ResourceGuard();
        ReflectionTestUtils.setField(g, "cpuThreshold", 0.7);
        ReflectionTestUtils.setField(g, "heapThreshold", 0.8);
        ReflectionTestUtils.setField(g, "cpuLoad", cpu);
        ReflectionTestUtils.setField(g, "heapUsed", heap);
        return g;
    }

    @Test
    void lowLoadIsNotBusy() {
        ResourceGuard g = guard(0.2, 0.3);
        assertFalse(g.busy());
        assertEquals("LOW", g.level());
    }

    @Test
    void cpuOverThresholdIsBusy() {
        ResourceGuard g = guard(0.85, 0.3);
        assertTrue(g.busy());
        assertEquals("HIGH", g.level());
    }

    @Test
    void heapOverThresholdIsBusyEvenIfCpuUnknown() {
        // cpuLoad = -1 表示平台取不到；此时必须只依据堆来判断
        ResourceGuard g = guard(-1, 0.9);
        assertTrue(g.busy(), "堆已超阈值，不能因为 CPU 读数缺失就判为不紧张");
        assertEquals("HIGH", g.level());
    }

    @Test
    void suggestedIntervalScalesWithPressure() {
        int base = 2000;
        assertEquals(2000, guard(0.1, 0.2).suggestedReportIntervalMs(base));
        assertEquals(4000, guard(0.6, 0.2).suggestedReportIntervalMs(base));   // MEDIUM → ×2
        assertEquals(10000, guard(0.95, 0.9).suggestedReportIntervalMs(base)); // HIGH → ×5 但封顶 10s
    }
}
