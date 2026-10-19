package com.smartagri.gateway.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等注解：基于请求头 {@code Idempotency-Key} 防重复提交 / 支持安全重试。
 *
 * <p>行为：</p>
 * <ul>
 *   <li>同一 key 的首次请求正常执行并缓存响应；</li>
 *   <li>窗口内携带相同 key 的重复请求直接返回首次缓存的响应（不再执行业务）；</li>
 *   <li>若相同 key 仍在处理中（并发重复），返回 409 冲突，提示稍后重试；</li>
 *   <li>未携带 {@code Idempotency-Key} 头的请求不做幂等约束（透传）。</li>
 * </ul>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
    /** 缓存 key 前缀（用于区分不同业务域）。 */
    String keyPrefix() default "";

    /** 幂等窗口（秒）：超过后 key 失效，可再次提交。 */
    int ttlSeconds() default 300;
}
