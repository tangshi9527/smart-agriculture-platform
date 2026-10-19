package com.smartagri.gateway;

import com.smartagri.gateway.metrics.ApiMetricsInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;

/**
 * API 治理 Web 配置：
 * <ul>
 *   <li>用 {@link ApiVersionRequestMappingHandlerMapping} 替换默认映射，启用 API 版本化；</li>
 *   <li>注册 {@link ApiMetricsInterceptor} 采集接口调用指标（调用数/延迟/异常率）。</li>
 * </ul>
 */
@Configuration
public class GatewayWebConfig implements WebMvcRegistrations, WebMvcConfigurer {

    private final ApiMetricsInterceptor metricsInterceptor;
    private final boolean legacyCompat;

    public GatewayWebConfig(ApiMetricsInterceptor metricsInterceptor,
                            @Value("${api.version.legacy-compat:true}") boolean legacyCompat) {
        this.metricsInterceptor = metricsInterceptor;
        this.legacyCompat = legacyCompat;
    }

    @Override
    public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
        return new ApiVersionRequestMappingHandlerMapping(legacyCompat);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(metricsInterceptor)
                .addPathPatterns("/api/**", "/auth/**")
                .excludePathPatterns("/actuator/**", "/error");
    }
}
