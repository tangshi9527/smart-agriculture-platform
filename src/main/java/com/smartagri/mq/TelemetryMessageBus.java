package com.smartagri.mq;

/**
 * 遥测消息总线：接入层与下游处理之间的解耦抽象。
 * 两种实现二选一（由 {@code mq.enabled} 决定）：
 * - 本地总线（默认）：进程内异步扇出，无需消息中间件，保证无外部组件也能启动；
 * - RabbitMQ：真实 broker 解耦，由 docker-compose 提供，需 {@code mq.enabled=true}。
 */
public interface TelemetryMessageBus {

    /** 发布一条遥测事件到总线 */
    void publish(TelemetryEvent event);

    /** 当前总线模式，用于日志 / 监控标识 */
    String mode();

    /**
     * 因下游处理不过来而被丢弃的事件数（累计）。
     * 本地总线有界队列满时会计数；RabbitMQ 由 broker 侧持久化与死信兜底，这里恒为 0。
     */
    default long droppedCount() {
        return 0L;
    }
}
