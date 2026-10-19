package com.smartagri.mq;

/**
 * 遥测事件消费者：每条下游处理链路实现一个消费者，由消息总线（本地或 RabbitMQ）驱动。
 * 消费者之间互不知道彼此存在，实现处理链路的解耦与独立伸缩。
 */
public interface TelemetryConsumer {

    /** 消费一条遥测事件 */
    void consume(TelemetryEvent event);

    /** 消费者名称（用于日志 / 指标标识） */
    String name();
}
