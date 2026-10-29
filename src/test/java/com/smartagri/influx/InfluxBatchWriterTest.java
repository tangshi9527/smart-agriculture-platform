package com.smartagri.influx;

import com.smartagri.device.Telemetry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 攒批器行为：一次网络往返处理一批，队列满丢而不阻塞，关闭时把尾巴刷完。
 *
 * <p>构造函数里给一个很大的刷新间隔（不启动后台 tick），所有用例都靠 {@link InfluxBatchWriter#flushNow()}
 * 手动触发，避免测试结果依赖线程调度时序。</p>
 */
class InfluxBatchWriterTest {

    private static final long NO_AUTO_TICK = 600_000L;

    private Telemetry tel(String device, long ts) {
        Telemetry t = new Telemetry();
        t.setDeviceId(device);
        t.setTs(ts);
        t.setMetrics(Map.of("temperature", 25.0));
        return t;
    }

    @Test
    void batchesManyTelemetryIntoFewWrites() {
        List<List<Telemetry>> calls = new CopyOnWriteArrayList<>();
        InfluxBatchWriter writer = new InfluxBatchWriter(1000, 10, NO_AUTO_TICK, batch -> {
            calls.add(List.copyOf(batch));
            return true;
        });

        for (int i = 0; i < 25; i++) {
            writer.offer(tel("dev-1", i));
        }
        writer.flushNow();

        // 25 条 / 每批 10 条 = 3 批（10 + 10 + 5），而上一版是 25 次 HTTP
        assertEquals(3, calls.size(), "25 条遥测应压成 3 次写入，而不是 25 次");
        assertEquals(10, calls.get(0).size());
        assertEquals(5, calls.get(2).size());
        assertEquals(25, writer.writtenCount());
        assertEquals(0, writer.droppedCount());
        writer.shutdown();
    }

    @Test
    void dropsWhenQueueFullInsteadOfBlockingCaller() {
        // 容量 5，写入回调故意慢：队列满时必须立即返回，不能把调用方拖住
        InfluxBatchWriter writer = new InfluxBatchWriter(5, 10, NO_AUTO_TICK, batch -> {
            sleep(50);
            return true;
        });

        long begin = System.currentTimeMillis();
        int accepted = 0;
        for (int i = 0; i < 200; i++) {
            if (writer.offer(tel("dev-1", i))) {
                accepted++;
            }
        }
        long cost = System.currentTimeMillis() - begin;

        assertEquals(5, accepted, "队列容量 5，只能收 5 条");
        assertEquals(195, writer.droppedCount(), "其余必须计入丢弃，而不是静默消失");
        assertTrue(cost < 500, "offer 绝不能阻塞，200 次入队耗时 " + cost + "ms");
        writer.shutdown();
    }

    @Test
    void reportsFailedBatchWithoutCountingItAsWritten() {
        AtomicInteger attempts = new AtomicInteger();
        InfluxBatchWriter writer = new InfluxBatchWriter(100, 10, NO_AUTO_TICK, batch -> {
            attempts.incrementAndGet();
            return false;
        });

        writer.offer(tel("dev-1", 1));
        writer.flushNow();

        assertEquals(1, attempts.get());
        assertEquals(1, writer.failedBatches(), "写失败必须单独计数");
        assertEquals(0, writer.writtenCount(), "失败的批不能算进已写入");
        writer.shutdown();
    }

    @Test
    void shutdownFlushesRemainingQueue() {
        List<Telemetry> got = new CopyOnWriteArrayList<>();
        InfluxBatchWriter writer = new InfluxBatchWriter(100, 4, NO_AUTO_TICK, batch -> {
            got.addAll(batch);
            return true;
        });
        for (int i = 0; i < 10; i++) {
            writer.offer(tel("dev-1", i));
        }

        writer.shutdown();

        assertEquals(10, got.size(), "正常停机必须把队列里剩下的点写完，否则每次重启都丢一段");
        assertFalse(writer.offer(tel("dev-1", 99)), "停机后不再接受新数据");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
