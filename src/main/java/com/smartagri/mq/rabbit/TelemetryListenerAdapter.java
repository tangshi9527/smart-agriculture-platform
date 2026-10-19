package com.smartagri.mq.rabbit;

import com.smartagri.mq.TelemetryEvent;
import com.smartagri.mq.consumer.AlertTelemetryConsumer;
import com.smartagri.mq.consumer.PushTelemetryConsumer;
import com.smartagri.mq.consumer.StorageTelemetryConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 队列监听器适配器：从遥测队列消费事件，扇出给三个下游消费者。
 *
 * <p><b>为什么每个消费者单独 try-catch：</b>Spring AMQP 默认是 AUTO ack——方法正常返回就 ack，
 * 抛异常就 nack + requeue。如果三个消费者串行且不隔离，storage 写 InfluxDB 失败会让整条消息重投，
 * 于是 alert 会重复告警、push 会重复推送，而且失败的那条会无限重投形成毒消息循环。
 * 这里逐个隔离：单个链路失败只影响自己，消息照常 ack，失败计数进日志与指标。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class TelemetryListenerAdapter {

    private final StorageTelemetryConsumer storage;
    private final AlertTelemetryConsumer alert;
    private final PushTelemetryConsumer push;

    private final AtomicLong storageFail = new AtomicLong();
    private final AtomicLong alertFail = new AtomicLong();
    private final AtomicLong pushFail = new AtomicLong();
    private final java.util.Map<String, Long> lastFailureAt = new java.util.concurrent.ConcurrentHashMap<>();

    @RabbitListener(queues = RabbitMqConfig.QUEUE)
    public void onTelemetry(TelemetryEvent event) {
        // 三条链路彼此隔离：任一失败都不影响其他两条，也不会触发整条消息 nack 重投
        try {
            storage.consume(event);
        } catch (Exception e) {
            logFailure("storage", storageFail, event, e);
        }
        // 告警是「不能丢」的链路：瞬时故障重试一次，仍失败才计数（丢一条就是漏报一次）
        try {
            alert.consume(event);
        } catch (Exception first) {
            try {
                alert.consume(event);
                log.warn("告警链路重试成功 device={} eventId={}（首次失败：{}）",
                        event.getDeviceId(), event.getEventId(), first.getMessage());
            } catch (Exception second) {
                logFailure("alert", alertFail, event, second);
            }
        }
        try {
            push.consume(event);
        } catch (Exception e) {
            logFailure("push", pushFail, event, e);
        }
    }

    private void logFailure(String link, AtomicLong counter, TelemetryEvent event, Exception e) {
        long n = counter.incrementAndGet();
        lastFailureAt.put(link, System.currentTimeMillis());
        log.error("消费者[{}]处理失败(累计 {} 次) device={} eventId={}: {}",
                link, n, event == null ? "-" : event.getDeviceId(),
                event == null ? "-" : event.getEventId(), e.getMessage());
    }

    /** 各链路累计失败次数，供 /api/system/health 暴露 */
    public java.util.Map<String, Long> failureCounters() {
        return java.util.Map.of(
                "storage", storageFail.get(),
                "alert", alertFail.get(),
                "push", pushFail.get());
    }

    /** 各链路最近一次失败时间戳（0 表示从未失败），用来区分「刚发生」和「启动时抖动」 */
    public java.util.Map<String, Long> lastFailureAt() {
        return java.util.Map.of(
                "storage", lastFailureAt.getOrDefault("storage", 0L),
                "alert", lastFailureAt.getOrDefault("alert", 0L),
                "push", lastFailureAt.getOrDefault("push", 0L));
    }
}
