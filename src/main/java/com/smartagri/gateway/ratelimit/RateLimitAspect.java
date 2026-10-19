package com.smartagri.gateway.ratelimit;

import com.smartagri.gateway.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 限流切面：在标注 {@link RateLimit} 的接口执行前按维度消费令牌桶，
 * 超限抛出 {@link RateLimitExceededException}（全局处理器返回 429），
 * 并写入 {@code X-RateLimit-Limit / Remaining / Reset} 响应头。
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAspect.class);

    private final RateLimitStore store;

    public RateLimitAspect(RateLimitStore store) {
        this.store = store;
    }

    @Before("@annotation(com.smartagri.gateway.ratelimit.RateLimit) || "
            + "@within(com.smartagri.gateway.ratelimit.RateLimit)")
    public void before(JoinPoint jp) {
        RateLimit anno = resolve(jp);
        if (anno == null) {
            return;
        }
        HttpServletRequest request = currentRequest();
        HttpServletResponse response = currentResponse();
        String key = buildKey(anno, jp, request);

        long refillMillis = (long) anno.refillSeconds() * 1000L;
        RateLimitResult r = store.tryConsume(key, anno.capacity(), anno.refillTokens(), refillMillis);

        if (response != null) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(anno.capacity()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(r.remaining()));
            response.setHeader("X-RateLimit-Reset", String.valueOf(r.resetMillis()));
        }

        if (!r.allowed()) {
            log.warn("限流触发：key={}, resetAfter={}ms", key, r.resetMillis());
            throw new RateLimitExceededException(
                    "请求过于频繁，请 " + (r.resetMillis() / 1000.0) + " 秒后重试");
        }
    }

    private RateLimit resolve(JoinPoint jp) {
        Method method = ((MethodSignature) jp.getSignature()).getMethod();
        RateLimit m = AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class);
        if (m != null) {
            return m;
        }
        return AnnotatedElementUtils.findMergedAnnotation(jp.getTarget().getClass(), RateLimit.class);
    }

    private String buildKey(RateLimit anno, JoinPoint jp, HttpServletRequest request) {
        String suffix = anno.key().isBlank()
                ? (jp.getTarget().getClass().getSimpleName() + "#" + ((MethodSignature) jp.getSignature()).getName())
                : anno.key();
        return switch (anno.keyType()) {
            case API -> "ratelimit:api:" + suffix;
            case IP -> "ratelimit:ip:" + clientIp(request);
            case USER -> {
                String user = currentUser();
                yield "ratelimit:user:" + (user == null ? clientIp(request) : user) + ":" + suffix;
            }
        };
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sa) {
            return sa.getRequest();
        }
        return null;
    }

    private HttpServletResponse currentResponse() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sa) {
            return sa.getResponse();
        }
        return null;
    }
}
