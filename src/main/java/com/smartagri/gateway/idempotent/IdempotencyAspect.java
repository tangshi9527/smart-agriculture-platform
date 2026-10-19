package com.smartagri.gateway.idempotent;

import com.smartagri.common.JsonUtil;
import com.smartagri.common.Result;
import com.smartagri.gateway.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
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
 * 幂等切面：对标注 {@link Idempotent} 的接口，按 {@code Idempotency-Key} 头做防重与重试缓存。
 */
@Aspect
@Component
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    private static final String HEADER = "Idempotency-Key";

    private final IdempotencyStore store;

    public IdempotencyAspect(IdempotencyStore store) {
        this.store = store;
    }

    @Around("@annotation(com.smartagri.gateway.idempotent.Idempotent) || "
            + "@within(com.smartagri.gateway.idempotent.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Idempotent anno = resolve(pjp);
        if (anno == null) {
            return pjp.proceed();
        }
        HttpServletRequest request = currentRequest();
        String idemKey = request == null ? null : request.getHeader(HEADER);
        if (idemKey == null || idemKey.isBlank()) {
            // 未携带幂等键：不做约束，直接透传
            return pjp.proceed();
        }

        String scope = currentUser() != null ? currentUser() : clientIp(request);
        String prefix = anno.keyPrefix().isBlank() ? "" : anno.keyPrefix() + ":";
        String key = "idempotency:" + scope + ":" + prefix + idemKey;

        String prev = store.putIfAbsent(key, IdempotencyStore.STATUS_IN_PROGRESS, anno.ttlSeconds());
        if (IdempotencyStore.STATUS_COMPLETED.equals(prev)) {
            String body = store.getCompletedBody(key);
            if (body != null) {
                log.debug("幂等命中，复用缓存响应：key={}", key);
                return JsonUtil.fromJson(body, Result.class);
            }
        } else if (prev != null) {
            throw new IdempotencyConflictException("相同 Idempotency-Key 正在处理，请稍后重试");
        }

        try {
            Object result = pjp.proceed();
            if (result instanceof Result<?> r) {
                store.setCompleted(key, JsonUtil.toJson(r), anno.ttlSeconds());
            }
            return result;
        } catch (Throwable t) {
            store.clear(key);
            throw t;
        }
    }

    private Idempotent resolve(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Idempotent m = AnnotatedElementUtils.findMergedAnnotation(method, Idempotent.class);
        if (m != null) {
            return m;
        }
        return AnnotatedElementUtils.findMergedAnnotation(pjp.getTarget().getClass(), Idempotent.class);
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
}
