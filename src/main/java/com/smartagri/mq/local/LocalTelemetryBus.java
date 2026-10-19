package com.smartagri.mq.local;

import com.smartagri.mq.TelemetryConsumer;
import com.smartagri.mq.TelemetryEvent;
import com.smartagri.mq.TelemetryMessageBus;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内消息总线（默认 / 兜底实现）。
 *
 * <p><b>每条链路独立线程池与队列（舱壁隔离）</b>：三个消费者的「丢点容忍度」完全不同——
 * storage 是尽力持久化，慢一点可以；alert 丢一条就是漏一次告警；push 丢一条下一条会补。
 * 如果共用一个队列，storage 被 InfluxDB 拖慢就会把 alert/push 的任务一起挤掉，
 * 用户看到的是「曲线在、告警没了、大屏卡了」，三个症状一个根因；
 * 而且告警延迟从 50ms 悄悄变成 500ms 也不会有人发现。</p>
 *
 * <p><b>队列有界</b>：{@code Executors.newFixedThreadPool} 内部是无界 {@code LinkedBlockingQueue}，
 * 下游一慢就无限堆积到 OOM。这里每条链路一个容量 8192 的 {@link ArrayBlockingQueue}，
 * 满了丢弃并<strong>按链路计数</strong>，丢弃日志带 deviceId，便于定位是哪台设备在打。</p>
 *
 * <p>不用 CallerRunsPolicy：调用方是 Netty IO 线程，让 IO 线程去跑存储写入等于堵死接入层。</p>
 */
@Slf4j
public class LocalTelemetryBus implements TelemetryMessageBus {

    /** 每条链路的默认队列容量 */
    public static final int DEFAULT_QUEUE_CAPACITY = 8192;
    /** storage 可能被 InfluxDB 拖慢，多给两个线程；alert/push 足够 */
    private static final int STORAGE_THREADS = 6;
    private static final int DEFAULT_THREADS = 4;

    private final List<TelemetryConsumer> consumers;
    /** consumer.name() -> 专属线程池 */
    private final Map<String, ThreadPoolExecutor> pools = new LinkedHashMap<>();
    /** 单测注入的执行器；非空时所有链路共用它（不做丢弃计数） */
    private final Executor injected;

    private final AtomicLong dropped = new AtomicLong();
    private final Map<String, AtomicLong> droppedByConsumer = new ConcurrentHashMap<>();
    private final AtomicLong lastWarnAt = new AtomicLong();

    public LocalTelemetryBus(List<TelemetryConsumer> consumers) {
        this(consumers, DEFAULT_QUEUE_CAPACITY);
    }

    public LocalTelemetryBus(List<TelemetryConsumer> consumers, int queueCapacity) {
        this.consumers = consumers;
        this.injected = null;
        for (TelemetryConsumer c : consumers) {
            pools.put(c.name(), newPool(c.name(), queueCapacity));
        }
    }

    /** 注入执行器（单测用）：背压由调用方保证，不提供丢弃计数 */
    public LocalTelemetryBus(List<TelemetryConsumer> consumers, Executor executor) {
        this.consumers = consumers;
        this.injected = executor;
    }

    private ThreadPoolExecutor newPool(String name, int queueCapacity) {
        int threads = "storage".equals(name) ? STORAGE_THREADS : DEFAULT_THREADS;
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                r -> {
                    Thread t = new Thread(r, "telemetry-" + name + "-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void publish(TelemetryEvent event) {
        for (TelemetryConsumer consumer : consumers) {
            Runnable task = () -> consumeSafely(consumer, event);
            if (injected != null) {
                injected.execute(task);
                continue;
            }
            try {
                pools.get(consumer.name()).execute(task);
            } catch (RejectedExecutionException e) {
                onDrop(consumer.name(), event);
            }
        }
    }

    private void consumeSafely(TelemetryConsumer consumer, TelemetryEvent event) {
        try {
            consumer.consume(event);
        } catch (Exception e) {
            log.error("消费者[{}]处理遥测事件异常 device={} eventId={}: {}",
                    consumer.name(), event.getDeviceId(), event.getEventId(), e.getMessage());
        }
    }

    /** 丢弃一条：按链路计数 + 限流告警（带 deviceId，否则只知道"丢了多少"不知道"谁在丢"） */
    private void onDrop(String consumer, TelemetryEvent event) {
        long total = dropped.incrementAndGet();
        long perLink = droppedByConsumer.computeIfAbsent(consumer, k -> new AtomicLong()).incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last > 1000 && lastWarnAt.compareAndSet(last, now)) {
            log.warn("本地总线[{}]队列已满，丢弃遥测 device={}（该链路累计 {} 条，总丢弃 {} 条）；"
                    + "建议开启 mq.enabled 切 RabbitMQ 或扩容", consumer, event.getDeviceId(), perLink, total);
        }
    }

    @Override
    public String mode() {
        return "local";
    }

    @Override
    public long droppedCount() {
        return dropped.get();
    }

    /** 按链路拆分的丢弃数，供 health 定位是哪条链路在丢 */
    public Map<String, Long> droppedByConsumer() {
        Map<String, Long> out = new LinkedHashMap<>();
        droppedByConsumer.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    /** 各链路当前排队数，供健康检查观察积压 */
    public Map<String, Integer> queueSizes() {
        Map<String, Integer> out = new LinkedHashMap<>();
        pools.forEach((k, v) -> out.put(k, v.getQueue().size()));
        return out;
    }
}
