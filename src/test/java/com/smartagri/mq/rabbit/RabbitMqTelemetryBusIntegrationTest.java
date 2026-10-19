package com.smartagri.mq.rabbit;

import com.smartagri.ai.AiProperties;
import com.smartagri.alert.AlertEngine;
import com.smartagri.device.Telemetry;
import com.smartagri.influx.InfluxService;
import com.smartagri.mq.TelemetryEvent;
import com.smartagri.mq.TelemetryMessageBus;
import com.smartagri.mq.consumer.AlertTelemetryConsumer;
import com.smartagri.mq.consumer.PushTelemetryConsumer;
import com.smartagri.mq.consumer.StorageTelemetryConsumer;
import com.smartagri.websocket.WebSocketPushService;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import com.github.fridujo.rabbitmq.mock.MockConnectionFactory;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RabbitMQ 解耦路径集成测试：用内存版 RabbitMQ broker（rabbitmq-mock）验证
 * 「接入层发布 -> 交换机/队列路由 -> 监听器扇出 -> 三个消费者」的真实端到端链路，
 * 无需外部消息中间件即可证明 RabbitMqConfig 的接线正确。
 */
@SpringBootTest(classes = {
        RabbitMqConfig.class,
        RabbitMqTelemetryBusIntegrationTest.MockBrokerConfig.class
}, properties = {"mq.enabled=true"}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RabbitMqTelemetryBusIntegrationTest {

    @Autowired
    TelemetryMessageBus bus;

    @Autowired
    InfluxService influx;          // mock，由 MockBrokerConfig 提供

    @Autowired
    WebSocketPushService push;     // mock

    @Autowired
    AlertEngine alertEngine;       // mock

    @Test
    void publishesAndConsumesThroughRealBroker() {
        TelemetryEvent event = TelemetryEvent.builder()
                .eventId("e1").deviceId("d1").ts(1L).producedAt(2L)
                .metrics(Map.of("temp", 25.5)).build();

        bus.publish(event);

        // 异步消费，等待消费者被驱动
        verify(influx, timeout(5000)).write(any(Telemetry.class));
        verify(push, timeout(5000)).pushTelemetry(any(Telemetry.class));
        verify(alertEngine, timeout(5000)).evaluate(any(Telemetry.class));
    }

    /** 用内存 mock broker 替换真实 RabbitMQ 连接，并注入三个消费者所用的下游服务 mock */
    @Configuration
    @EnableAutoConfiguration
    static class MockBrokerConfig {

        @Bean
        @Primary
        public ConnectionFactory connectionFactory() {
            return new CachingConnectionFactory(new MockConnectionFactory());
        }

        @Bean
        public InfluxService influxService() {
            return mock(InfluxService.class);
        }

        @Bean
        public AlertEngine alertEngine() {
            AlertEngine engine = mock(AlertEngine.class);
            when(engine.evaluate(any(Telemetry.class))).thenReturn(Collections.emptyList());
            return engine;
        }

        @Bean
        public WebSocketPushService pushService() {
            return mock(WebSocketPushService.class);
        }

        @Bean
        public StorageTelemetryConsumer storage(InfluxService influx) {
            return new StorageTelemetryConsumer(influx);
        }

        @Bean
        public AlertTelemetryConsumer alert(AlertEngine alertEngine,
                                            WebSocketPushService push) {
            AiProperties ai = new AiProperties();
            ai.getExplain().setAuto(false);
            return new AlertTelemetryConsumer(alertEngine, push,
                    mock(com.smartagri.ai.AlertExplainer.class), ai);
        }

        @Bean
        public PushTelemetryConsumer push(WebSocketPushService push) {
            return new PushTelemetryConsumer(push);
        }
    }
}
