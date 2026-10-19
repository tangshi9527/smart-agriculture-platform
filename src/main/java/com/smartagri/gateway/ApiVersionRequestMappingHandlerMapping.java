package com.smartagri.gateway;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo.BuilderConfiguration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 支持 API 版本化的 HandlerMapping。
 *
 * <p>核心思路：重写 {@link #getMappingForMethod} —— 对标注了 {@link ApiVersion} 的控制器，
 * 返回一个<b>同时包含旧版 {@code /api/**} 与版本化 {@code /api/v{N}/**} 两条路径</b>的单一
 * {@link RequestMappingInfo}。这样每个方法只注册一次、无歧义、也无「控制器晚于 Mapping 初始化」
 * 的时序问题（标准 {@code afterPropertiesSet} 即可完成）。</p>
 *
 * <p>版本兼容：默认旧版与 v1 并存；将 {@code api.version.legacy-compat} 置为 {@code false}
 * 即可「优雅下线」旧版路径，仅保留 {@code /api/v{N}}。</p>
 */
public class ApiVersionRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

    /** 是否保留旧版 /api/** 路由（向后兼容）。 */
    private final boolean legacyCompat;

    public ApiVersionRequestMappingHandlerMapping(boolean legacyCompat) {
        this.legacyCompat = legacyCompat;
    }

    public ApiVersionRequestMappingHandlerMapping() {
        this(true);
    }

    @Override
    protected RequestMappingInfo getMappingForMethod(@NonNull java.lang.reflect.Method method,
                                                    @NonNull Class<?> handlerType) {
        RequestMappingInfo info = super.getMappingForMethod(method, handlerType);
        if (info == null) {
            return null;
        }
        ApiVersion av = AnnotatedElementUtils.findMergedAnnotation(handlerType, ApiVersion.class);
        if (av == null) {
            return info; // 未版本化的控制器保持原样
        }

        Set<String> patterns = info.getPatternValues();
        if (patterns.isEmpty()) {
            return info;
        }
        String prefix = "/api/v" + av.value();
        List<String> combined = new ArrayList<>();
        for (String p : patterns) {
            if (legacyCompat) {
                combined.add(p); // 旧版路径
            }
            String v = versionPattern(p, prefix);
            if (v != null) {
                combined.add(v); // 版本化路径
            }
        }
        if (combined.isEmpty()) {
            return info;
        }

        RequestMappingInfo.Builder builder = RequestMappingInfo.paths(combined.toArray(new String[0]));
        BuilderConfiguration cfg = getBuilderConfiguration();
        if (cfg != null) {
            builder.options(cfg);
        }
        if (!info.getMethodsCondition().isEmpty()) {
            builder.methods(info.getMethodsCondition().getMethods().toArray(new RequestMethod[0]));
        }
        if (!info.getParamsCondition().isEmpty()) {
            builder.params(info.getParamsCondition().getExpressions().stream()
                    .map(Object::toString).toArray(String[]::new));
        }
        if (!info.getHeadersCondition().isEmpty()) {
            builder.headers(info.getHeadersCondition().getExpressions().stream()
                    .map(Object::toString).toArray(String[]::new));
        }
        if (!info.getConsumesCondition().isEmpty()) {
            builder.consumes(info.getConsumesCondition().getExpressions().stream()
                    .map(Object::toString).toArray(String[]::new));
        }
        if (!info.getProducesCondition().isEmpty()) {
            builder.produces(info.getProducesCondition().getExpressions().stream()
                    .map(Object::toString).toArray(String[]::new));
        }
        return builder.build();
    }

    /** 把 {@code /api/xxx} 改写为 {@code /api/vN/xxx}；非 /api 前缀返回 null（不版本化）。 */
    private String versionPattern(String pattern, String prefix) {
        if (pattern != null && pattern.startsWith("/api/")) {
            return prefix + pattern.substring("/api".length());
        }
        if (pattern != null && pattern.equals("/api")) {
            return prefix;
        }
        return null;
    }
}
