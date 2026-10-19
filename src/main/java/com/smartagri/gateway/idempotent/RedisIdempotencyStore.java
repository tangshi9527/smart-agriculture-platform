package com.smartagri.gateway.idempotent;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的分布式幂等存储（多实例共享）。
 *
 * <p>状态与响应体分两键存储：{@code key} 存状态，{@code key:body} 存响应 JSON，
 * 均带 TTL。Redis 不可用时由 {@link FallbackIdempotencyStore} 降级到本地内存。</p>
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redis;

    public RedisIdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public String putIfAbsent(String key, String status, long ttlSeconds) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, status, ttlSeconds, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(ok)) {
            return null;
        }
        return redis.opsForValue().get(key);
    }

    @Override
    public void setCompleted(String key, String body, long ttlSeconds) {
        redis.opsForValue().set(key + ":body", body, ttlSeconds, TimeUnit.SECONDS);
        redis.opsForValue().set(key, STATUS_COMPLETED, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    public String getCompletedBody(String key) {
        String status = redis.opsForValue().get(key);
        if (!STATUS_COMPLETED.equals(status)) {
            return null;
        }
        return redis.opsForValue().get(key + ":body");
    }

    @Override
    public void clear(String key) {
        redis.delete(java.util.List.of(key, key + ":body"));
    }
}
