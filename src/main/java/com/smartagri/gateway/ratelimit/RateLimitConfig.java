package com.smartagri.gateway.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 限流 Bean 装配：本地桶始终可用；Redis 桶在存在 {@link StringRedisTemplate} 时启用，
 * 由 {@link FallbackRateLimitStore} 做主存储并按需降级。
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public LocalRateLimitStore localRateLimitStore() {
        return new LocalRateLimitStore();
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    public RedisRateLimitStore redisRateLimitStore(StringRedisTemplate redis) {
        return new RedisRateLimitStore(redis);
    }

    @Bean
    @Primary
    public RateLimitStore rateLimitStore(LocalRateLimitStore local,
                                         @org.springframework.beans.factory.annotation.Autowired(required = false)
                                         RedisRateLimitStore redis) {
        if (redis != null) {
            return new FallbackRateLimitStore(redis, local);
        }
        return local;
    }
}
