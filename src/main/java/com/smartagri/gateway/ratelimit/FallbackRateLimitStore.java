package com.smartagri.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 兜底型限流存储：优先走 Redis（分布式），Redis 异常时降级为本地内存桶。
 *
 * <p><b>降级不是单向门</b>：这里本来就是「每次调用先试 Redis，失败才用本地」，天然可恢复。
 * 但这样有个真实代价——Redis 不可达时<strong>每个请求都要付一次连接超时</strong>，
 * 接口 P99 会被这个超时拖着走。所以加一个短熔断窗口：失败后 5 秒内直接走本地，
 * 窗口一到自动再试 Redis。既避免请求雪崩，也保证 Redis 恢复后最多 5s 就切回去。</p>
 */
public class FallbackRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(FallbackRateLimitStore.class);

    /** 失败后的冷却窗口：这段时间内不再尝试 Redis，省掉每请求一次的超时代价 */
    private static final long COOL_DOWN_MS = 5000;

    private final RateLimitStore redis;
    private final RateLimitStore local;

    /** Redis 冷却到期时间戳；0 表示当前可用 */
    private final AtomicLong redisCoolDownUntil = new AtomicLong(0);

    public FallbackRateLimitStore(RateLimitStore redis, RateLimitStore local) {
        this.redis = redis;
        this.local = local;
    }

    @Override
    public RateLimitResult tryConsume(String key, long capacity, long refillTokens, long refillMillis) {
        long now = System.currentTimeMillis();
        if (now < redisCoolDownUntil.get()) {
            return local.tryConsume(key, capacity, refillTokens, refillMillis);
        }
        try {
            return redis.tryConsume(key, capacity, refillTokens, refillMillis);
        } catch (Exception e) {
            // 进入冷却窗口：后续 5s 内直接本地，避免每个请求都等一次连接超时
            if (redisCoolDownUntil.getAndSet(now + COOL_DOWN_MS) <= now) {
                log.warn("Redis 限流不可用，降级本地内存桶并冷却 {}ms（多实例限流暂时不精确）：{}",
                        COOL_DOWN_MS, e.getMessage());
            }
            return local.tryConsume(key, capacity, refillTokens, refillMillis);
        }
    }
}
