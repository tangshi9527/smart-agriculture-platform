package com.smartagri.gateway.ratelimit;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存令牌桶（单机版）。
 *
 * <p>采用连续补充公式：<code>tokens += elapsed * refillTokens / refillMillis</code>，
 * 再与容量取小。线程安全（每个 key 一把锁）。适合无 Redis 或单元测试场景，
 * 也是 {@link FallbackRateLimitStore} 在 Redis 不可用时的兜底实现。</p>
 */
public class LocalRateLimitStore implements RateLimitStore {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(String key, long capacity, long refillTokens, long refillMillis) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        return bucket.consume(capacity, refillTokens, refillMillis);
    }

    /** 单个令牌桶。 */
    private static final class Bucket {
        private long tokens;
        private long lastRefillNanos;

        Bucket(long capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized RateLimitResult consume(long capacity, long refillTokens, long refillMillis) {
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos > 0) {
                // 统一换算成毫秒：elapsedNanos / 1_000_000 = 毫秒
                long added = elapsedNanos * refillTokens / (refillMillis * 1_000_000L);
                if (added > 0) {
                    tokens = Math.min(capacity, tokens + added);
                    lastRefillNanos = now;
                }
            }
            boolean allowed;
            if (tokens >= 1) {
                tokens -= 1;
                allowed = true;
            } else {
                allowed = false;
            }
            // 距离补充一个令牌的时间（毫秒）；桶满时给一个周期作为保守值
            long resetMillis = allowed ? 0 : Math.max(1, refillMillis / Math.max(1, refillTokens));
            return new RateLimitResult(allowed, tokens, resetMillis);
        }
    }
}
