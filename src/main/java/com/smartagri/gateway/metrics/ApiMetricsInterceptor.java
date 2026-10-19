package com.smartagri.gateway.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.concurrent.TimeUnit;

/**
 * API 调用监控拦截器：为每个命中 {@code /api/**} 或 {@code /auth/**} 的请求记录
 * 调用次数、耗时与异常（5xx）计数，指标接入 Micrometer（已被 P1 的
 * {@code /actuator/prometheus} 暴露给 Prometheus 抓取）。
 *
 * <p>uri 标签采用 Spring 匹配到的路径模板（如 {@code /api/devices/{id}/shadow}），
 * 避免用真实带参 URI 造成标签基数爆炸。</p>
 */
public class ApiMetricsInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = "api.metrics.start";

    private final MeterRegistry registry;

    public ApiMetricsInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object startObj = request.getAttribute(START_ATTR);
        if (!(startObj instanceof Long start)) {
            return;
        }
        long elapsedNanos = System.nanoTime() - start;
        double seconds = elapsedNanos / 1_000_000_000.0;

        String uri = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (uri == null || uri.isBlank()) {
            uri = request.getRequestURI();
        }
        String method = request.getMethod();
        int status = response.getStatus();

        registry.counter("api_requests_total",
                "uri", uri, "method", method, "status", String.valueOf(status)).increment();

        registry.timer("api_request_duration_seconds", "uri", uri, "method", method)
                .record((long) (seconds * 1_000_000_000), TimeUnit.NANOSECONDS);

        if (status >= 500 || ex != null) {
            registry.counter("api_server_errors_total", "uri", uri, "method", method).increment();
        }
    }
}
