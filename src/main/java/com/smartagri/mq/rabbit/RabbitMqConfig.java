package com.smartagri.mq.rabbit;

import com.smartagri.mq.TelemetryMessageBus;
import com.smartagri.mq.consumer.AlertTelemetryConsumer;
import com.smartagri.mq.consumer.PushTelemetryConsumer;
import com.smartagri.mq.consumer.StorageTelemetryConsumer;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 装配：当 {@code mq.enabled=true} 时生效，提供真实消息队列解耦路径。
 * <p>
 * 拓扑：主题交换机 {@link #EXCHANGE} -> 队列 {@link #QUEUE}（路由键 {@link #ROUTING_KEY}）。
 * 接入层通过 {@link RabbitMqTelemetryBus} 发布，{@link TelemetryListenerAdapter} 消费并扇出到三个下游消费者。
 * 消息体使用 Jackson JSON 序列化（含类型信息），生产 / 消费端共享同一事件类。
 */
@Configuration
@ConditionalOnProperty(prefix = "mq", name = "enabled", havingValue = "true")
public class RabbitMqConfig {

    public static final String EXCHANGE = "agri.telemetry.exchange";
    public static final String QUEUE = "agri.telemetry.queue";
    public static final String ROUTING_KEY = "telemetry.ingest";

    /** 死信交换机 / 队列：反复处理失败或反序列化失败的消息落这里，便于人工排查而不是无限重投 */
    public static final String DLX = "agri.telemetry.dlx";
    public static final String DLQ = "agri.telemetry.dlq";
    public static final String DLQ_ROUTING_KEY = "telemetry.dead";

    @Bean
    public MessageConverter telemetryMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate telemetryRabbitTemplate(ConnectionFactory connectionFactory,
                                                  MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        // 发布确认 / 退回回调： broker 不可达或路由失败时记录，便于排查（不阻塞发布）
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                org.slf4j.LoggerFactory.getLogger(RabbitMqConfig.class)
                        .warn("遥测消息未收到 broker 确认: {}", cause);
            }
        });
        template.setReturnsCallback(returned -> {
            org.slf4j.LoggerFactory.getLogger(RabbitMqConfig.class)
                    .warn("遥测消息被退回 exchange={}, routingKey={}: {}",
                            returned.getExchange(), returned.getRoutingKey(), returned.getMessage());
        });
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(4);
        factory.setPrefetchCount(64);
        // 消费者抛异常时不再无条件 requeue：
        // 默认 AUTO ack + requeue 会让一条毒消息（如反序列化失败）无限重投，打满 CPU 且刷爆日志。
        // 业务异常已在 TelemetryListenerAdapter 内部逐个隔离，走不到这里；
        // 走到这里的是基础设施级失败，直接丢弃 → 由队列的 DLX 收进死信队列。
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public Declarables telemetryTopology() {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        // 主队列绑定死信交换机：超过重试上限 / 被拒绝的消息自动转投 DLQ
        Queue queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY);

        TopicExchange dlx = new TopicExchange(DLX, true, false);
        Queue dlq = QueueBuilder.durable(DLQ).build();
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(DLQ_ROUTING_KEY);

        return new Declarables(exchange, queue, binding, dlx, dlq, dlqBinding);
    }

    /**
     * 保证拓扑（交换机/队列/绑定）在所有单例 Bean 创建完成后才声明。
     * 避免 {@code RabbitAdmin} 早于 {@link #telemetryTopology()} 初始化而漏声明，
     * 否则发布时会报「No exchange named ...」。该步幂等，重复启动与集成测试都适用。
     */
    @Bean
    public SmartInitializingSingleton declareTelemetryTopologyOnStartup(
            org.springframework.amqp.core.AmqpAdmin amqpAdmin) {
        return () -> amqpAdmin.initialize();
    }

    @Bean
    public TelemetryMessageBus telemetryMessageBus(RabbitTemplate rabbitTemplate) {
        return new RabbitMqTelemetryBus(rabbitTemplate);
    }

    @Bean
    public TelemetryListenerAdapter telemetryListenerAdapter(
            StorageTelemetryConsumer storage,
            AlertTelemetryConsumer alert,
            PushTelemetryConsumer push) {
        return new TelemetryListenerAdapter(storage, alert, push);
    }
}
