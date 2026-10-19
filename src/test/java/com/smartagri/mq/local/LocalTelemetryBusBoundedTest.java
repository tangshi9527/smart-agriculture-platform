package com.smartagri.mq.local;

import com.smartagri.mq.TelemetryConsumer;
import com.smartagri.mq.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地总线的背压边界：修复前用的是 {@code Executors.newFixedThreadPool}，
 * 内部无界队列会一直堆到 OOM；现在换成有界队列 + 丢弃计数。
 */
class LocalTelemetryBusBoundedTest {

    @Test
    void dropsAndCountsWhenQueueIsFull() throws Exception {
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger consumed = new AtomicInteger();

        TelemetryConsumer slow = new TelemetryConsumer() {
            @Override
            public String name() {
                return "slow";
            }

            @Override
            public void consume(TelemetryEvent event) {
                started.countDown();
                try {
                    block.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                consumed.incrementAndGet();
            }
        };

        // 容量 2：8 个线程 + 2 个排队位，灌 64 条必然溢出
        LocalTelemetryBus bus = new LocalTelemetryBus(List.of(slow), 2);
        try {
            for (int i = 0; i < 64; i++) {
                bus.publish(event());
            }
            assertTrue(started.await(2, TimeUnit.SECONDS), "消费线程应已启动");
            long dropped = bus.droppedCount();
            assertTrue(dropped > 0, "队列满后必须计数，而不是静默堆积到 OOM");
            assertTrue(dropped < 64, "不应全部丢弃，说明线程池确实在消费");
            assertTrue(consumed.get() <= 8, "8 个线程都在阻塞处理中，已完成数不应超过线程数");
        } finally {
            block.countDown();
        }
    }

    @Test
    void healthyBusDropsNothing() {
        AtomicInteger consumed = new AtomicInteger();
        TelemetryConsumer fast = new TelemetryConsumer() {
            @Override
            public String name() {
                return "fast";
            }

            @Override
            public void consume(TelemetryEvent event) {
                consumed.incrementAndGet();
            }
        };
        LocalTelemetryBus bus = new LocalTelemetryBus(List.of(fast), 1024);
        for (int i = 0; i < 20; i++) {
            bus.publish(event());
        }
        assertEquals(0, bus.droppedCount());
    }

    private TelemetryEvent event() {
        return TelemetryEvent.builder()
                .eventId("e").deviceId("d1").ts(1L).producedAt(1L)
                .metrics(Map.of("temperature", 25.0)).build();
    }
}
