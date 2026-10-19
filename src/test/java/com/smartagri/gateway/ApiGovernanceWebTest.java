package com.smartagri.gateway;

import com.smartagri.gateway.idempotent.IdempotencyAspect;
import com.smartagri.gateway.idempotent.IdempotencyConfig;
import com.smartagri.common.GlobalExceptionHandler;
import com.smartagri.gateway.ratelimit.RateLimitAspect;
import com.smartagri.gateway.ratelimit.RateLimitConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * API 治理 Web 切片测试：在真实 DispatcherServlet 中验证
 * 版本化双路由、限流（429 + 响应头）、幂等（重复键复用缓存）、调用监控（指标计数）。
 *
 * <p>关闭安全过滤器（addFilters=false），聚焦治理切面本身；鉴权已由 P0/P1 单独保障。</p>
 */
@WebMvcTest(excludeAutoConfiguration = {MetricsAutoConfiguration.class,
                PrometheusMetricsExportAutoConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
@Import({TestGatewayController.class, GatewayWebConfig.class, GatewayMetricsConfig.class, RateLimitAspect.class,
        IdempotencyAspect.class, RateLimitConfig.class, IdempotencyConfig.class, AopAutoConfiguration.class,
        GlobalExceptionHandler.class, ApiGovernanceWebTest.MeterConfig.class})
class ApiGovernanceWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    /** 提供一个简单 MeterRegistry，确保指标拦截器可注入且断言可读取。 */
    @Configuration
    static class MeterConfig {
        @Bean
        SimpleMeterRegistry simpleMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void versioning_supportsBothLegacyAndV1Routes() throws Exception {
        // 旧版 /api/test/ping 仍可用（向后兼容）
        mockMvc.perform(get("/api/test/ping"))
                .andExpect(status().isOk());
        // 版本化 /api/v1/test/ping 同时可用
        mockMvc.perform(get("/api/v1/test/ping"))
                .andExpect(status().isOk());
    }

    @Test
    void rateLimit_rejectsAfterCapacityWithHeaders() throws Exception {
        // 前两次放行
        mockMvc.perform(get("/api/test/limited"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().exists("X-RateLimit-Remaining"));
        mockMvc.perform(get("/api/test/limited")).andExpect(status().isOk());
        // 第三次触发限流 -> 429
        mockMvc.perform(get("/api/test/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    void idempotency_reusesCachedResultForSameKey() throws Exception {
        String body1 = mockMvc.perform(post("/api/test/idem")
                        .header("Idempotency-Key", "k-abc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 相同 Idempotency-Key -> 复用首次缓存响应（整体 JSON 一致）
        String body2 = mockMvc.perform(post("/api/test/idem")
                        .header("Idempotency-Key", "k-abc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(body1, body2);

        // 不带 Idempotency-Key -> 正常透传，每次不同
        String body3 = mockMvc.perform(post("/api/test/idem"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNotEquals(body1, body3);
    }

    @Test
    void metrics_recordsApiRequestCount() throws Exception {
        double before = meterRegistry.find("api_requests_total").counters().stream()
                .mapToDouble(c -> c.count()).sum();

        mockMvc.perform(get("/api/v1/test/ping")).andExpect(status().isOk());

        double after = meterRegistry.find("api_requests_total").counters().stream()
                .mapToDouble(c -> c.count()).sum();

        assertTrue(after > before, "调用后应记录 api_requests_total 指标");
    }
}
