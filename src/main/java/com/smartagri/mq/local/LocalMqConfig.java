package com.smartagri.mq.local;

import com.smartagri.mq.TelemetryConsumer;
import com.smartagri.mq.TelemetryMessageBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 本地总线装配：当 {@code mq.enabled=false}（默认）时生效，提供进程内异步消息总线。
 * 不创建任何 RabbitMQ 连接，服务零外部依赖即可运行。
 */
@Configuration
@ConditionalOnProperty(prefix = "mq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalMqConfig {

    @Bean
    public TelemetryMessageBus telemetryMessageBus(List<TelemetryConsumer> consumers) {
        return new LocalTelemetryBus(consumers);
    }
}
