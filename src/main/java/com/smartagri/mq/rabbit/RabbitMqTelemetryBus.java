package com.smartagri.mq.rabbit;

import com.smartagri.mq.TelemetryEvent;
import com.smartagri.mq.TelemetryMessageBus;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * RabbitMQ 消息总线实现：将遥测事件发布到主题交换机，由绑定队列的消费者异步处理。
 * 发布即返回（异步确认），设备接入线程不被下游处理阻塞。
 */
public class RabbitMqTelemetryBus implements TelemetryMessageBus {

    private final RabbitTemplate rabbitTemplate;

    public RabbitMqTelemetryBus(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(TelemetryEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE, RabbitMqConfig.ROUTING_KEY, event);
    }

    @Override
    public String mode() {
        return "rabbitmq";
    }
}
