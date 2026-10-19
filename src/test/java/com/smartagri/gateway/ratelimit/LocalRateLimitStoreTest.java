package com.smartagri.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地令牌桶逻辑测试：验证突发上限、连续消费拒绝与补充恢复。
 */
class LocalRateLimitStoreTest {

    private final LocalRateLimitStore store = new LocalRateLimitStore();

    @Test
    void consumesUpToCapacityThenRejects() {
        String key = "k1";
        // 容量 2，周期 60s：前两次放行，第三次被限流
        RateLimitResult r1 = store.tryConsume(key, 2, 2, 60_000);
        assertTrue(r1.allowed());
        assertEquals(1, r1.remaining());

        RateLimitResult r2 = store.tryConsume(key, 2, 2, 60_000);
        assertTrue(r2.allowed());
        assertEquals(0, r2.remaining());

        RateLimitResult r3 = store.tryConsume(key, 2, 2, 60_000);
        assertFalse(r3.allowed());
        assertEquals(0, r3.remaining());
        assertTrue(r3.resetMillis() > 0);
    }

    @Test
    void refillsAfterWindow() throws InterruptedException {
        String key = "k2";
        store.tryConsume(key, 1, 1, 200); // 用掉唯一令牌
        RateLimitResult rejected = store.tryConsume(key, 1, 1, 200);
        assertFalse(rejected.allowed());

        Thread.sleep(300); // 等待一个补充周期
        RateLimitResult recovered = store.tryConsume(key, 1, 1, 200);
        assertTrue(recovered.allowed());
    }

    @Test
    void independentKeysDoNotShareBucket() {
        RateLimitResult a = store.tryConsume("a", 1, 1, 60_000);
        RateLimitResult b = store.tryConsume("b", 1, 1, 60_000);
        assertTrue(a.allowed());
        assertTrue(b.allowed());
    }
}
