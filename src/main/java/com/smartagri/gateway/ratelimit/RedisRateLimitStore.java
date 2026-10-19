package com.smartagri.gateway.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * 基于 Redis 的分布式令牌桶（集群/多实例下共享限流状态）。
 *
 * <p>使用 Lua 脚本保证「读取-计算-写回」原子性，避免并发竞态。Redis 不可用时由
 * {@link FallbackRateLimitStore} 自动降级到本地内存桶，保证限流不中断。</p>
 */
public class RedisRateLimitStore implements RateLimitStore {

    private static final String LUA =
            "local key = KEYS[1] "
          + "local capacity = tonumber(ARGV[1]) "
          + "local refillTokens = tonumber(ARGV[2]) "
          + "local refillMillis = tonumber(ARGV[3]) "
          + "local now = tonumber(ARGV[4]) "
          + "local b = redis.call('HMGET', key, 'tokens', 'ts') "
          + "local tokens = tonumber(b[1]) "
          + "local ts = tonumber(b[2]) "
          + "if tokens == nil then tokens = capacity; ts = now end "
          + "local elapsed = now - ts "
          + "if elapsed > 0 then "
          + "  local added = elapsed * refillTokens / refillMillis "
          + "  if added > 0 then tokens = math.min(capacity, tokens + added); ts = now end "
          + "end "
          + "local allowed = 0 "
          + "if tokens >= 1 then tokens = tokens - 1; allowed = 1 end "
          + "redis.call('HMSET', key, 'tokens', tokens, 'ts', now) "
          + "redis.call('PEXPIRE', key, capacity * refillMillis / refillTokens + refillMillis) "
          + "return {allowed, tokens}";

    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>(LUA, List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitResult tryConsume(String key, long capacity, long refillTokens, long refillMillis) {
        List<? extends Number> res = redis.execute(SCRIPT,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillTokens),
                String.valueOf(refillMillis),
                String.valueOf(System.currentTimeMillis()));
        if (res == null || res.size() < 2) {
            throw new IllegalStateException("redis rate-limit script returned empty");
        }
        long allowed = res.get(0).longValue();
        long remaining = res.get(1).longValue();
        boolean ok = allowed == 1;
        long resetMillis = ok ? 0 : Math.max(1, refillMillis);
        return new RateLimitResult(ok, remaining, resetMillis);
    }
}
