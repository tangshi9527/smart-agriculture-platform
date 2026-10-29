package com.smartagri.influx;

import com.smartagri.device.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 遥测写入的批量攒批器：把「每条上报一次 HTTP」变成「一批几百条一次 HTTP」。
 *
 * <p><b>为什么需要它。</b>上一版 {@code InfluxService.write()} 对每条遥测直接调
 * {@code WriteApiBlocking.writePoint()}，一次上报就是一次同步 HTTP 往返。200 台设备
 * 每 2 秒一报是 100 次/秒，storage 链路 6 个线程的全部时间都花在等网络上——线程数
 * 再多也没用，瓶颈在往返次数不在线程数。更讽刺的是降级回写路径
 * {@code flushMemoryBacklog()} 本来就按 {@code WRITE_BATCH_SIZE=5000} 批量写，
 * 也就是说<b>正常路径比降级路径还慢</b>。这里把正常路径补上攒批。</p>
 *
 * <p><b>为什么队列必须有界、满了直接丢。</b>攒批必然要有缓冲。无界缓冲在下游变慢时
 * 会把堆吃干（本项目正是踩过这个 OOM 才把线程池队列改成有界的），所以这里同样给上限，
 * 满了记一次丢弃就返回，<b>绝不阻塞调用方</b>——调用方是 storage 消费者线程，
 * 阻塞它会顺着有界队列一路反压到接入层。</p>
 *
 * <p><b>为什么单线程。</b>攒批之后一批 500 条点、单次 HTTP 在十几毫秒量级，一个线程
 * 每秒能刷 2500 条以上，而 200 设备稳态只有 100 条/秒。多线程在这里只会增加
 * 与 InfluxDB 的并发连接数，不会提高吞吐（瓶颈是服务端单次写入的解析开销）。</p>
 */
class InfluxBatchWriter {

    /** 一批怎么写、写失败算什么，由 {@link InfluxService} 决定（它才知道该不该降级） */
    @FunctionalInterface
    interface Sink {
        /**
         * @return {@code true} 表示这批已经有归宿——写成功了，或者被服务端明确拒绝、
         *         重试毫无意义（由 Sink 负责计数）；
         *         {@code false} 表示传输层失败，数据已被 Sink 转存到降级区等恢复后补写
         */
        boolean write(List<Telemetry> batch);
    }

    private static final Logger log = LoggerFactory.getLogger(InfluxBatchWriter.class);

    /**
     * 一个刷新周期最多连发几批。
     * 队列积压时只发一批会永远追不上（每 200ms 走一趟，每趟只吐 500 条，一积压就再也刷不完），
     * 所以要能在单个周期内连续排空；同时留上界，避免一次 tick 长时间占住写锁。
     */
    private static final int MAX_BATCHES_PER_TICK = 8;

    private final BlockingQueue<Telemetry> queue;
    private final ScheduledExecutorService flusher;
    private final Sink sink;
    private final int batchSize;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private volatile boolean running = false;

    InfluxBatchWriter(int capacity, int batchSize, long flushIntervalMs, Sink sink) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        this.batchSize = Math.max(1, batchSize);
        this.sink = sink;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "influx-batch-writer");
            // daemon：不能让一个后台攒批线程把 JVM 关住
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(10L, flushIntervalMs);
        this.flusher.scheduleWithFixedDelay(this::drainAndWrite, interval, interval, TimeUnit.MILLISECONDS);
        this.running = true;
    }

    /**
     * 入队一条遥测。满则丢弃并计数，<b>不阻塞</b>。
     *
     * @return true 表示已入队等待落库；false 表示被丢弃（此时 {@link #droppedCount()} 已 +1）
     */
    boolean offer(Telemetry t) {
        if (t == null) {
            return false;
        }
        if (!running || !queue.offer(t)) {
            dropped.incrementAndGet();
            return false;
        }
        accepted.incrementAndGet();
        return true;
    }

    private void drainAndWrite() {
        try {
            for (int i = 0; i < MAX_BATCHES_PER_TICK && !queue.isEmpty(); i++) {
                List<Telemetry> batch = new ArrayList<>(batchSize);
                queue.drainTo(batch, batchSize);
                if (batch.isEmpty()) {
                    return;
                }
                if (sink.write(batch)) {
                    written.addAndGet(batch.size());
                } else {
                    failedBatches.incrementAndGet();
                }
            }
        } catch (Throwable e) {
            // 这个线程一死，存储链路就静默停摆——比写失败严重得多，必须自己兜住继续跑
            log.warn("攒批写入线程捕获异常，下个周期继续: {}", e.getMessage());
        }
    }

    /** 停掉调度线程，并把队列里剩下的点尽力写完。 */
    void shutdown() {
        running = false;
        flusher.shutdown();
        try {
            if (!flusher.awaitTermination(2, TimeUnit.SECONDS)) {
                flusher.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 收尾：此时调度线程已停，没有并发消费者，可以安全地一次性排空
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 1000) {
            List<Telemetry> batch = new ArrayList<>(batchSize);
            queue.drainTo(batch, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            try {
                if (sink.write(batch)) {
                    written.addAndGet(batch.size());
                }
            } catch (Throwable ignore) {
                break;
            }
        }
        long left = queue.size();
        if (left > 0) {
            log.warn("关闭时仍有 {} 条遥测没能写出（进程即将退出，这部分数据丢失）", left);
        }
    }

    /** 立即同步刷一次（单测用，避免等 200ms 的调度周期） */
    void flushNow() {
        drainAndWrite();
    }

    long acceptedCount() {
        return accepted.get();
    }

    long droppedCount() {
        return dropped.get();
    }

    long writtenCount() {
        return written.get();
    }

    long failedBatches() {
        return failedBatches.get();
    }

    int pending() {
        return queue.size();
    }
}
