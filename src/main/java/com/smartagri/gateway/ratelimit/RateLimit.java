package com.smartagri.gateway.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解：基于令牌桶算法，对指定维度（IP / 登录用户 / 接口）做速率控制。
 *
 * <p>示例：<code>@RateLimit(capacity = 10, refillTokens = 10, refillSeconds = 60)</code>
 * 表示每个维度每分钟最多 10 次请求，突发上限 10。</p>
 *
 * <p>支持标注在方法（仅该方法）或类（该类下所有接口）上。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    /** 令牌桶容量（突发上限）。 */
    int capacity() default 100;

    /** 每个补充周期放入的令牌数。 */
    int refillTokens() default 100;

    /** 补充周期（秒）。 */
    int refillSeconds() default 60;

    /** 限流维度。 */
    KeyType keyType() default KeyType.USER;

    /** 自定义 key 片段（用于在同一维度下区分不同接口）。默认取「类名#方法名」。 */
    String key() default "";
}
