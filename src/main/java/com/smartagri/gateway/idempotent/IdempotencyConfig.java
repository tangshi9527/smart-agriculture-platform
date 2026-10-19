package com.smartagri.gateway.idempotent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 幂等 Bean 装配：本地存储始终可用；存在 {@link StringRedisTemplate} 时启用 Redis 存储，
 * 由 {@link FallbackIdempotencyStore} 做主存储并按需降级。
 */
@Configuration
public class IdempotencyConfig {

    @Bean
    public LocalIdempotencyStore localIdempotencyStore() {
        return new LocalIdempotencyStore();
    }

    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    public RedisIdempotencyStore redisIdempotencyStore(StringRedisTemplate redis) {
        return new RedisIdempotencyStore(redis);
    }

    @Bean
    @Primary
    public IdempotencyStore idempotencyStore(LocalIdempotencyStore local,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RedisIdempotencyStore redis) {
        if (redis != null) {
            return new FallbackIdempotencyStore(redis, local);
        }
        return local;
    }
}
