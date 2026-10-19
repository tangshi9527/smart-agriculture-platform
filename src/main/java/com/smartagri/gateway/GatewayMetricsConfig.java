package com.smartagri.gateway;

import com.smartagri.gateway.metrics.ApiMetricsInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 调用监控拦截器装配（独立配置类，避免与 {@link GatewayWebConfig} 的构造注入形成循环依赖）。
 */
@Configuration
public class GatewayMetricsConfig {

    @Bean
    public ApiMetricsInterceptor apiMetricsInterceptor(MeterRegistry registry) {
        return new ApiMetricsInterceptor(registry);
    }
}
