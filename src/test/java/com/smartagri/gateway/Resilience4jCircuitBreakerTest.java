package com.smartagri.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 熔断语义测试（纯库级，不依赖 Spring）：验证连续失败后熔断器打开，
 * 在等待期内直接拒绝请求，体现「限流熔断」保护下游的能力。
 */
class Resilience4jCircuitBreakerTest {

    @Test
    void opensAfterFailureThresholdAndRejects() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();

        CircuitBreaker cb = CircuitBreaker.of("telemetry", config);
        AtomicInteger calls = new AtomicInteger();

        Supplier<String> decorated = CircuitBreaker.decorateSupplier(cb, () -> {
            calls.incrementAndGet();
            throw new RuntimeException("downstream unavailable");
        });

        // 连续 4 次失败 -> 失败率 100% -> 熔断打开
        for (int i = 0; i < 4; i++) {
            try {
                decorated.get();
            } catch (Exception ignored) {
                // 熔断打开时抛 CallNotPermittedException；故障时抛原始异常
            }
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // 打开状态下再来请求应被熔断直接拒绝（不再打到下游）
        int before = calls.get();
        try {
            decorated.get();
        } catch (Exception ignored) {
        }
        assertEquals(before, calls.get()); // 下游未被调用
    }
}
