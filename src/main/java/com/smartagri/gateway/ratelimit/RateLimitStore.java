package com.smartagri.gateway.ratelimit;

/**
 * 限流存储抽象：令牌桶状态可落本地内存或 Redis（分布式）。
 */
public interface RateLimitStore {
    /**
     * 尝试消费一个令牌。
     *
     * @param key          限流维度 key
     * @param capacity     桶容量
     * @param refillTokens 每周期补充令牌数
     * @param refillMillis 补充周期（毫秒）
     * @return 消费结果（是否放行 / 剩余 / 重置时间）
     */
    RateLimitResult tryConsume(String key, long capacity, long refillTokens, long refillMillis);
}
