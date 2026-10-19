package com.smartagri.gateway.idempotent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 兜底型幂等存储：优先 Redis，异常时降级本地内存，保证幂等能力不因 Redis 故障而失效。
 *
 * <p>与限流同理，加短熔断窗口：Redis 不可达时不必每个请求都等一次连接超时，
 * 5 秒冷却后自动再试，恢复后即切回分布式幂等。</p>
 */
public class FallbackIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(FallbackIdempotencyStore.class);
    private static final long COOL_DOWN_MS = 5000;

    private final IdempotencyStore redis;
    private final IdempotencyStore local;
    private final AtomicLong redisCoolDownUntil = new AtomicLong(0);

    public FallbackIdempotencyStore(IdempotencyStore redis, IdempotencyStore local) {
        this.redis = redis;
        this.local = local;
    }

    private boolean cooling() {
        return System.currentTimeMillis() < redisCoolDownUntil.get();
    }

    private void enterCoolDown(Exception e) {
        long now = System.currentTimeMillis();
        if (redisCoolDownUntil.getAndSet(now + COOL_DOWN_MS) <= now) {
            log.warn("Redis 幂等不可用，降级本地并冷却 {}ms（跨实例幂等暂时失效）：{}",
                    COOL_DOWN_MS, e.getMessage());
        }
    }

    @Override
    public String putIfAbsent(String key, String status, long ttlSeconds) {
        if (cooling()) {
            return local.putIfAbsent(key, status, ttlSeconds);
        }
        try {
            return redis.putIfAbsent(key, status, ttlSeconds);
        } catch (Exception e) {
            enterCoolDown(e);
            return local.putIfAbsent(key, status, ttlSeconds);
        }
    }

    @Override
    public void setCompleted(String key, String body, long ttlSeconds) {
        if (cooling()) {
            local.setCompleted(key, body, ttlSeconds);
            return;
        }
        try {
            redis.setCompleted(key, body, ttlSeconds);
        } catch (Exception e) {
            enterCoolDown(e);
            local.setCompleted(key, body, ttlSeconds);
        }
    }

    @Override
    public String getCompletedBody(String key) {
        if (cooling()) {
            return local.getCompletedBody(key);
        }
        try {
            return redis.getCompletedBody(key);
        } catch (Exception e) {
            enterCoolDown(e);
            return local.getCompletedBody(key);
        }
    }

    @Override
    public void clear(String key) {
        if (!cooling()) {
            try {
                redis.clear(key);
            } catch (Exception e) {
                enterCoolDown(e);
            }
        }
        // 本地始终清，避免降级期间写入的脏键在恢复后残留
        local.clear(key);
    }
}
