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

    @Test
    void 单tick连发批数可配且就是吞吐天花板() {
        // 队列里堆 100 条，每批 10 条，单 tick 只允许发 3 批 → 一次 flushNow 只应写出 30 条
        List<Telemetry> got = new CopyOnWriteArrayList<>();
        InfluxBatchWriter writer = new InfluxBatchWriter(200, 10, NO_AUTO_TICK, 3, batch -> {
            got.addAll(batch);
            return true;
        });
        for (int i = 0; i < 100; i++) {
            writer.offer(tel("dev-1", i));
        }

        writer.flushNow();

        assertEquals(30, got.size(), "单 tick 最多发 maxBatchesPerTick 批，剩下的留给下个 tick");
        assertEquals(70, writer.pending());
        // 天花板 = 3 × 10 × 1000 / 200ms = 150 点/秒。扩容时该调哪个参数，看这个数
        assertEquals(150.0, writer.theoreticalCeilingPerSec(200), 0.01);
        writer.shutdown();
    }

    @Test
    void 单tick批数下限收敛为1不会静默停摆() {
        // 配成 0 或负数时，若直接用作循环上界，drainAndWrite 一次都不进，攒批线程就静默停摆了。
        // 所以构造函数把它收敛到 1——这里锁住这个行为。
        List<Telemetry> got = new CopyOnWriteArrayList<>();
        InfluxBatchWriter writer = new InfluxBatchWriter(100, 10, NO_AUTO_TICK, 0, batch -> {
            got.addAll(batch);
            return true;
        });
        for (int i = 0; i < 25; i++) {
            writer.offer(tel("dev-1", i));
        }

        writer.flushNow();

        assertEquals(1, writer.maxBatchesPerTick());
        assertEquals(10, got.size(), "收敛为 1 批：至少还在动，而不是一条都不写");
        writer.shutdown();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
