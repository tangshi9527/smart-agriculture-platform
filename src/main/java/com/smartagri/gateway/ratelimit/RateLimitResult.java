package com.smartagri.gateway.ratelimit;

/**
 * 令牌桶消费结果。
 *
 * @param allowed    是否放行（拿到令牌）
 * @param remaining  消费后剩余令牌数
 * @param resetMillis 距离下一次可补充一个令牌的毫秒数（仅当被限流时有意义）
 */
public record RateLimitResult(boolean allowed, long remaining, long resetMillis) {
}
