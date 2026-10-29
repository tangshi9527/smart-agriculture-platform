package com.smartagri.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.Bucket;
import com.influxdb.client.domain.Organization;
import com.influxdb.client.domain.Task;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.exceptions.InfluxException;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.smartagri.device.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InfluxDB 时序存储层：
 * - 高频遥测写入原始库 agri-raw
 * - 启动时为原始库建立 1 分钟降采样任务，写入 agri-1m（连续查询/降采样等价物）
 * - 长周期聚合优先读降采样库，减少需要扫描的点数（具体差距取决于数据量与窗口，本项目未做基准测试）
 * <p>
 * 容错：若 InfluxDB 不可用（例如本地未启动 docker-compose），自动降级为进程内内存时序存储，
 * 保证写入/查询/降采样 benchmark 接口仍可演示，服务不依赖外部组件也能启动。
 */
@Service
public class InfluxService {

    private static final Logger log = LoggerFactory.getLogger(InfluxService.class);

    private final InfluxDBClient client;
    @Value("${influx.org:smart-agri}")
    private String org;
    @Value("${influx.bucket:agri-raw}")
    private String rawBucket;
    @Value("${influx.downsample-bucket:agri-1m}")
    private String dsBucket;

    /** 数据生命周期：原始层默认保留 30 天，降采样层 365 天；0 表示永久保留 */
    @Value("${influx.raw-retention-days:30}")
    private int rawRetentionDays;

    @Value("${influx.downsample-retention-days:365}")
    private int dsRetentionDays;

    /**
     * 降采样任务滞后多久算「已经停摆」。降采样 task 每 1 分钟跑一次，默认给 10 倍余量。
     * 之所以不设 2~3 分钟：Influx 自身的调度抖动 + 一次慢查询就可能超过 3 分钟，
     * 误报比不报更伤（运维会开始无视这条告警）。
     */
    @Value("${influx.task-lag-threshold-ms:600000}")
    private long taskLagThresholdMs;

    /** 降采样停摆时，把原始库 retention 临时拉长到多少天（抢救窗口） */
    @Value("${influx.rescue-retention-days:365}")
    private int rescueRetentionDays;

    /** 停摆后自动补算的历史范围上限（小时）。超过这个长度只补最近一段，其余靠抢救期兜住 */
    @Value("${influx.backfill-max-hours:72}")
    private int backfillMaxHours;

    private WriteApiBlocking writeApi;
    private volatile boolean influxAvailable = false;

    // ===== 落库攒批（P0-1） =====
    /** 批量写入器；init() 成功后才创建，单测直接 new 时为 null（走同步写老路径） */
    private InfluxBatchWriter batchWriter;

    /** 攒批参数：字段带默认值，是为了单测 {@code new InfluxService(mock)} 不走 Spring 注入时也有合理值 */
    @Value("${influx.write.batch-size:500}")
    private int writeBatchSize = 500;
    @Value("${influx.write.flush-interval-ms:200}")
    private long writeFlushIntervalMs = 200;
    @Value("${influx.write.queue-capacity:20000}")
    private int writeQueueCapacity = 20000;

    /**
     * 连续失败多少批才整体降级。
     *
     * <p>取 3 而不是 1：网络超时、连接池短暂耗尽、服务端一次 GC 停顿都会让单次写入失败，
     * 一失败就切内存会把全平台拖进降级态（几百台设备的查询立刻全部走内存），
     * 而对手很可能第 2 秒就恢复了。连续 3 批（默认约 600ms 内）都失败才算真的不通。</p>
     */
    @Value("${influx.write.fail-threshold:3}")
    private int writeFailThreshold = 3;

    /** 连续写入失败批次数；成功一批即归零 */
    private final java.util.concurrent.atomic.AtomicInteger consecutiveWriteFailures =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /**
     * 被服务端明确拒绝、按「不可重试」丢弃的点数。
     *
     * <p>这类丢弃和降级淘汰不是一回事：这些点是<b>数据本身有问题</b>（字段类型冲突、
     * 行协议语法错、token 无权限），补写一万次也是同样结果。它们必须和"丢了多少点"
     * 分开暴露，否则运维会去查网络，而真正该改的是上报字段。</p>
     */
    private final java.util.concurrent.atomic.AtomicLong rejectedPoints = new java.util.concurrent.atomic.AtomicLong(0);
    /**
     * 因字段值为 NaN / ±Infinity 而被剔除的字段数。
     *
     * <p>单独计数而不是并进 {@link #rejectedPoints}：这两个数指向完全不同的排查方向。
     * 前者说明<b>传感器在吐非法值</b>（该查设备或接入层清洗），后者说明<b>写入被服务端拒绝</b>
     * （该查字段类型冲突或权限）。混在一起就等于没数。</p>
     */
    private final java.util.concurrent.atomic.AtomicLong invalidFields = new java.util.concurrent.atomic.AtomicLong(0);
    /** 写锁：攒批线程与降级补写线程都会碰 writeApi，串行化避免行协议批次交叉 */
    private final Object writeLock = new Object();

    /** 内存降级存储：device -> 该设备原始采样点（有界，防止长跑 OOM） */
    private static final int MAX_MEM_POINTS_PER_DEVICE = 3000;
    private final ConcurrentHashMap<String, List<Rec>> memRaw = new ConcurrentHashMap<>();
    /** 内存降级：1 分钟降采样缓存 device|field -> 按分钟聚合的点（查询时按窗口聚合，不落盘，基本不增长） */
    private final ConcurrentHashMap<String, List<Rec>> memDs = new ConcurrentHashMap<>();

    /** 恢复时批量补写的批大小 */
    private static final int WRITE_BATCH_SIZE = 5000;
    /** 保留最近多少个降级窗口（供查询端判断数据可信度） */
    private static final int MAX_GAPS = 20;

    /** 本轮降级开始时间；0 表示当前未降级 */
    private final java.util.concurrent.atomic.AtomicLong degradedSince = new java.util.concurrent.atomic.AtomicLong(0);

    /** 降级原因：startup-probe-failed / write-failed / cpu-pressure —— health 里直接告诉运维"要不要救火" */
    private volatile String degradeReason = "startup-probe-failed";
    /** 下一次探测的最早时间（毫秒），配合指数退避避免 ping-pong 尖峰 */
    private volatile long nextProbeAt = 0;
    /** 连续探测失败次数，用于退避与观测 */
    private final java.util.concurrent.atomic.AtomicInteger recoverAttempts = new java.util.concurrent.atomic.AtomicInteger(0);

    @org.springframework.beans.factory.annotation.Value("${influx.recover-probe-ms:30000}")
    private long recoverBaseMs;

    /**
     * 资源守卫（测试环境下为 null）。
     * 用字段注入而不是构造器注入，是为了不破坏 {@code new InfluxService(mockClient)} 这类单测。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.smartagri.capacity.ResourceGuard resourceGuard;
    /** 已结束的降级窗口 [start, end, durablePoints, evictedPoints] */
    private final java.util.concurrent.ConcurrentLinkedDeque<long[]> gaps = new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** 累计因内存有界淘汰而永久丢失的点数（补写救不回来的那部分） */
    private final java.util.concurrent.atomic.AtomicLong evictedCounter = new java.util.concurrent.atomic.AtomicLong();
    /** 本轮降级开始时的淘汰计数快照，用于算"这次降级丢了多少" */
    private volatile long evictedAtDegradeStart = 0;

    // ===== 降采样任务健康（8.3 级联失效防护） =====

    /** 默认滞后阈值：配置缺失（单测直接 new）时的兜底 */
    private static final long DEFAULT_TASK_LAG_MS = 600_000L;
    private static final int DEFAULT_RESCUE_DAYS = 365;
    private static final int DEFAULT_BACKFILL_MAX_HOURS = 72;
    /** 补算按 1 小时一片，避免一次 Flux 查询把服务端和本机一起打满 */
    private static final long BACKFILL_CHUNK_MS = 3_600_000L;

    /** 降采样是否跟得上原始库；false 时 ds 库正在形成空洞 */
    private volatile boolean downsampleHealthy = true;
    /** 本实例启动时刻，用于给降采样探测留启动宽限（新库刚建时 ds 必然是空的） */
    private final long startedAt = System.currentTimeMillis();
    /**
     * 最近一次「观测到降采样库有新鲜数据」的时间戳（毫秒）。
     *
     * <p>注意这是<b>观测值</b>，不是 task 自己写回来的 marker。两种做法的取舍：
     * marker 语义更准，但要在已有的 Flux task 里加 {@code union(array.from(...))}，
     * 而那段 Flux 一旦写错，task 本身就挂了——等于用一个新的失效点去监控旧的失效点，
     * 且本地没有真实 Influx 可验证。观测法的代价是「task 成功」要靠间接推断，
     * 但它复用的是正常巡检也在走的查询路径，不会新增失效点。</p>
     */
    private volatile long lastTaskSuccessAt = 0;
    /** 最近一次健康探测时间（0 表示还没探过） */
    private volatile long lastTaskCheckAt = 0;
    /**
     * 最近一次判定的结论：unknown / no-data / ingestion-stopped / ok / stale。
     *
     * <p>为什么需要它：只给一个 {@code healthy} 布尔会在<b>还没探过</b>时也输出 true，
     * 那是虚假健康——监控看到 true 就放心了，而实际上一次都没检查。
     * 未探过必须能区分于"探过且正常"。</p>
     */
    private volatile String lastVerdict = "unknown";
    /** 原始库 retention 当前是否处于「抢救值」而非配置值 */
    private volatile boolean retentionRescued = false;
    /** 停摆期间发现「历史区间超出补算上限、没能补齐」——此时 ds 库有永久空洞 */
    private volatile boolean backfillPending = false;
    /** 连续判定为停摆的次数，用于抑制重复日志 */
    private final java.util.concurrent.atomic.AtomicLong staleProbes = new java.util.concurrent.atomic.AtomicLong();
    /** 本轮停摆起点；0 表示当前健康 */
    private volatile long taskOutageStart = 0;
    /** 已结束的降采样停摆窗口 [start, end]：这段区间的 ds 数据不可信 */
    private final java.util.concurrent.ConcurrentLinkedDeque<long[]> taskGaps = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public InfluxService(InfluxDBClient client) {
        this.client = client;
    }

    @PostConstruct
    public void init() {
        try {
            writeApi = client.getWriteApiBlocking();
            ensureBucket(rawBucket);
            ensureBucket(dsBucket);
            ensureDownsampleTask();
            // 攒批线程只在初始化成功后拉起：连不上 Influx 时它没有意义，
            // 而且此时写必然失败，只会把队列灌满再丢掉
            batchWriter = new InfluxBatchWriter(writeQueueCapacity, writeBatchSize,
                    writeFlushIntervalMs, this::sinkWriteBatch);
            influxAvailable = true;
            log.info("InfluxDB 初始化完成: raw={}, downsample={}；落库攒批 batchSize={} flushInterval={}ms",
                    rawBucket, dsBucket, writeBatchSize, writeFlushIntervalMs);
        } catch (Exception e) {
            influxAvailable = false;
            enterDegraded("startup-probe-failed");
            log.warn("InfluxDB 不可用，已降级为内存时序存储（请确认 InfluxDB 已启动且 token 有权限）: {}", e.getMessage());
        }
    }

    /** 停机时把攒批队列里剩下的点写完，避免正常重启丢掉最后几百条 */
    @PreDestroy
    public void shutdown() {
        if (batchWriter != null) {
            batchWriter.shutdown();
        }
    }

    /**
     * 降级恢复探测。
     *
     * <p><b>关键：恢复时必须先把降级期间的内存点补写回 Influx。</b>
     * 上一版只做了「ping 通 → 重建 bucket/task → 切回」，漏了补写——后果是查询端在
     * {@code [T_降级, T_恢复]} 这段区间拿到的是空数据，而降采样是 1 分钟窗口聚合，
     * 缺失窗口不会返回空值而是"少算一块"，调用方看到的是**平缓的假曲线**，不会报错。
     * 这比降级本身更危险：降级至少 health 里能看出来，恢复后的空洞没有任何信号。</p>
     */
    @Scheduled(fixedDelayString = "${influx.recover-tick-ms:5000}")
    public void probeInflux() {
        if (influxAvailable) {
            // 正常态也要做一件事：把「写入抖动时临时收进内存」的点补写回去。
            // 不加这一步的话，一次未达降级阈值的失败（1~2 批）会把那几百个点永久卡在
            // 内存里——因为恢复探测只在 influxAvailable=false 时才跑，而它根本没降级。
            // 表现是：曲线在 T 那一刻缺一块，health 全绿，没人知道为什么。
            flushPendingBacklog();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextProbeAt) {
            return;
        }
        // 资源紧张时不尝试恢复：ping 通只说明 InfluxDB 活着，不代表本机扛得住一次批量写。
        // 直接切回的结果是「切回 → 写入打满 CPU → 判失败 → 又降级」，每 30 秒一次尖峰，
        // 比一直用内存更折腾。所以恢复必须同时满足「连接通」和「资源允许」。
        if (resourceGuard != null && !resourceGuard.canRecover()) {
            degradeReason = "cpu-pressure";
            scheduleNextProbe(now);
            return;
        }
        try {
            if (!client.ping()) {
                scheduleNextProbe(now);
                return;
            }
            ensureBucket(rawBucket);
            ensureBucket(dsBucket);
            ensureDownsampleTask();
            int flushed = flushMemoryBacklog();
            influxAvailable = true;
            recoverAttempts.set(0);
            nextProbeAt = 0;
            degradeReason = null;
            // 切换瞬间仍可能有请求写进内存（此时 influxAvailable 还是 false），收尾再刷一次
            int tail = flushMemoryBacklog();
            long end = System.currentTimeMillis();
            long start = degradedSince.getAndSet(0L);
            if (start > 0) {
                recordGap(start, end, flushed + tail);
                log.info("InfluxDB 已恢复：补写 {} + {} 个内存点，降级窗口 {}ms 已记入 gaps",
                        flushed, tail, end - start);
            } else {
                log.info("InfluxDB 已恢复，补写 {} + {} 个内存点", flushed, tail);
            }
        } catch (Exception e) {
            log.debug("InfluxDB 仍未恢复: {}", e.getMessage());
            scheduleNextProbe(now);
        }
    }

    /** 指数退避：30s → 60s → 120s → 240s → 300s 封顶 */
    private void scheduleNextProbe(long now) {
        int n = recoverAttempts.incrementAndGet();
        long delay = Math.min(recoverBaseMs << Math.min(n - 1, 4), 300_000L);
        nextProbeAt = now + delay;
    }

    /**
     * 把内存降级期间累积的点批量写回原始库。
     *
     * @return 成功写回的条数；写失败会抛异常，由调用方保持降级状态（内存数据不清空，下一轮重试）
     */
    private int flushMemoryBacklog() {
        synchronized (writeLock) {
            return flushMemoryBacklog(writeApi);
        }
    }

    /**
     * 正常态下的补写：把写入抖动期间临时收进内存的点补回 Influx。
     *
     * <p>只在 {@code memRaw} 非空时才真正动作，因此稳态下每次 5 秒的 tick 只是一次
     * {@code isEmpty()} 判断，不产生任何网络请求。</p>
     */
    private void flushPendingBacklog() {
        if (memRaw.isEmpty() || writeApi == null) {
            return;
        }
        try {
            int n = flushMemoryBacklog();
            if (n > 0) {
                log.info("补写抖动期间暂存的 {} 个点（未触发降级）", n);
            }
        } catch (Exception e) {
            // 还是不通：留在内存里等下一个 tick，不影响正常写入
            log.debug("暂存点补写失败，下个周期重试: {}", e.getMessage());
        }
    }

    /** 包可见重载：便于单测直接验证补写逻辑，不必起真实 InfluxDB */
    int flushMemoryBacklog(WriteApiBlocking api) {
        if (api == null || memRaw.isEmpty()) {
            return 0;
        }
        int total = 0;
        List<Point> batch = new ArrayList<>(WRITE_BATCH_SIZE);
        for (Map.Entry<String, List<Rec>> entry : memRaw.entrySet()) {
            List<Rec> src = entry.getValue();
            List<Rec> snapshot;
            synchronized (src) {
                snapshot = new ArrayList<>(src);
            }
            for (Rec r : snapshot) {
                batch.add(Point.measurement("telemetry")
                        .addTag("device", r.device)
                        .addField(r.field, r.value)
                        .time(Instant.ofEpochMilli(r.ts), WritePrecision.MS));
                if (batch.size() >= WRITE_BATCH_SIZE) {
                    api.writePoints(rawBucket, org, batch);
                    total += batch.size();
                    batch.clear();
                }
            }
            // 只删除已写回的部分，保留快照之后新进来的点
            synchronized (src) {
                int n = Math.min(snapshot.size(), src.size());
                src.subList(0, n).clear();
            }
            if (src.isEmpty()) {
                memRaw.remove(entry.getKey(), src);
            }
        }
        if (!batch.isEmpty()) {
            api.writePoints(rawBucket, org, batch);
            total += batch.size();
        }
        return total;
    }

    private void enterDegraded(String reason) {
        degradeReason = reason;
        if (degradedSince.compareAndSet(0L, System.currentTimeMillis())) {
            // 只在"从正常进入降级"这一刻记快照，重复调用不覆盖
            evictedAtDegradeStart = evictedCounter.get();
        }
    }

    /**
     * 记录一次降级窗口。
     *
     * @param durablePoints 补写成功、这段曲线可信的点数
     */
    private void recordGap(long start, long end, int durablePoints) {
        long evicted = Math.max(0, evictedCounter.get() - evictedAtDegradeStart);
        gaps.addLast(new long[]{start, end, durablePoints, evicted});
        while (gaps.size() > MAX_GAPS) {
            gaps.pollFirst();
        }
    }

    /** 降级窗口列表：调用方可据此判断某段历史数据是否可信 */
    public List<Map<String, Object>> degradedGaps() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (long[] g : gaps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", g[0]);
            m.put("end", g[1]);
            m.put("durableMs", g[1] - g[0]);
            // 光有"降了多久"不够：30s 降级可能零丢失（容量够、补写成功），也可能丢了大半（内存淘汰）。
            // 这两个字段才决定查询端敢不敢把这段曲线当准的用。
            m.put("durablePoints", g[2]);
            m.put("evictedPoints", g[3]);
            m.put("trustworthy", g[3] == 0);
            out.add(m);
        }
        return out;
    }

    /** 当前是否仍处于降级状态，以及从何时开始 */
    public long degradedSince() {
        return degradedSince.get();
    }

    /** 降级原因：influx-down（要救火）还是 cpu-pressure（演示态正常，采样该降） */
    public String degradeReason() {
        return degradeReason;
    }

    /** 下一次探测时间；0 表示未排期（已恢复或从未降级） */
    public long nextProbeAt() {
        return nextProbeAt;
    }

    public int recoverAttempts() {
        return recoverAttempts.get();
    }

    /** 累计因内存有界淘汰永久丢失的点数 */
    public long evictedPoints() {
        return evictedCounter.get();
    }

    /**
     * 落库通道的观测数据。
     *
     * <p>四个「丢」必须分开给，因为它们对应四种完全不同的处置：
     * 队列满丢（写入跟不上，该加线程/减负载）、服务端拒绝（数据本身有问题，该改上报字段）、
     * 非法字段剔除（传感器坏了，该查设备）、内存淘汰（降级太久，该修 Influx）。
     * 合成一个 {@code dropped} 会让运维照着错的怀疑方向去排查。</p>
     */
    public Map<String, Object> writeStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        InfluxBatchWriter w = batchWriter;
        m.put("mode", w == null ? "sync" : "batched");
        m.put("batchSize", writeBatchSize);
        m.put("flushIntervalMs", writeFlushIntervalMs);
        if (w != null) {
            m.put("accepted", w.acceptedCount());
            m.put("written", w.writtenCount());
            m.put("pending", w.pending());
            // 队列满而丢：写入速度跟不上上报速度（Influx 变慢或攒批线程被拖住）
            m.put("droppedQueueFull", w.droppedCount());
            m.put("failedBatches", w.failedBatches());
        }
        m.put("rejectedByServer", rejectedPoints.get());
        m.put("invalidFieldsSkipped", invalidFields.get());
        m.put("evictedFromMemory", evictedCounter.get());
        m.put("memoryPendingPoints", memoryPointCount());
        m.put("consecutiveFailures", consecutiveWriteFailures.get());
        m.put("degradeThreshold", writeFailThreshold);
        return m;
    }

    /** 内存降级缓冲里现有的点数（health 与单测共用） */
    public int memoryPointCount() {
        int n = 0;
        for (List<Rec> l : memRaw.values()) {
            synchronized (l) {
                n += l.size();
            }
        }
        return n;
    }

    // ===================== 单测钩子 =====================
    // 这几个方法只给单测用：验证"一批遥测怎么落库、失败算不算降级"不需要起真实 InfluxDB，
    // 而 init() 又必须先连上服务端才能跑。生产路径不会调用它们。

    /** 包可见：手动置位可用状态，模拟"已经连上 Influx" */
    void setInfluxAvailableForTest(boolean available) {
        this.influxAvailable = available;
    }

    /** 包可见：注入 mock 的写入通道 */
    void setWriteApiForTest(WriteApiBlocking api) {
        this.writeApi = api;
    }

    /** 包可见：直接喂一批遥测走落库与错误分类逻辑 */
    boolean writeBatchForTest(List<Telemetry> batch) {
        return sinkWriteBatch(batch);
    }

    // ===================== 降采样任务健康检查 =====================

    /**
     * 探一次降采样任务是否跟得上原始库。
     *
     * <p>为什么需要它：上一轮给 bucket 加了 retention（raw 30 天）是为了防磁盘炸，
     * 但降采样 task 一旦停摆（Influx 重启丢 task、task 被误 pause、Flux 报错），
     * 新数据仍然每天写进 raw，却没有任何东西写进 1m 库。30 天后 raw 的 retention
     * 把没被降采样的数据淘汰——数据永久丢失，且<b>没有任何信号</b>：服务正常、
     * 曲线正常（只是短了），dv 层看起来一切健康。
     * 也就是说「修复磁盘增长」这件事本身，引入了一条新的静默丢失路径。</p>
     *
     * <p>三步处理：① 判定滞后 → ② 立刻把 raw retention 拉长到抢救值（先止血，
     * 保证数据不被淘汰）→ ③ 恢复后按小时分片把空洞补算进 1m 库，补算成功才把
     * retention 调回配置值（否则调回去就等于给空洞判了死刑）。</p>
     */
    @Scheduled(fixedDelayString = "${influx.task-health-ms:300000}")
    public void probeDownsampleTask() {
        if (!influxAvailable || writeApi == null) {
            // 整体不可用时有另一套降级逻辑在跑，这里再报一次只会把 health 刷成噪音
            return;
        }
        long now = System.currentTimeMillis();
        // 启动宽限：新部署时 ds 库是空的，task 要等下一个 1 分钟边界才可能产出第一个窗口。
        // 没有这段宽限，第一次探测必然判停摆并触发一次"抢救"（还会写 error 日志），
        // 属于自己给自己造的误报。
        if (now - startedAt < lagThreshold()) {
            return;
        }
        lastTaskCheckAt = now;
        try {
            long rawLatest = latestPointTime(rawBucket, "telemetry");
            long dsLatest = latestPointTime(dsBucket, "telemetry_1m");
            checkDownsampleFreshness(rawLatest, dsLatest, now);
        } catch (Exception e) {
            // 探测本身失败不算 task 停摆：查询挂了可能是网络抖动，下一轮再看
            log.debug("降采样健康探测失败（不影响判定）: {}", e.getMessage());
        }
    }

    /**
     * 判定逻辑（包可见，单测直接喂两个时间戳，不必起真实 InfluxDB）。
     *
     * @param rawLatest 原始库最新点时间戳（毫秒）；0/负数表示查不到
     * @param dsLatest  降采样库最新点时间戳（毫秒）；0/负数表示查不到
     */
    void checkDownsampleFreshness(long rawLatest, long dsLatest, long now) {
        lastTaskCheckAt = now;
        if (rawLatest <= 0) {
            // 压根没数据，判断不了 task 的生死，宁可不说
            lastVerdict = "no-data";
            return;
        }
        if (now - rawLatest > lagThreshold()) {
            // 采集本身就停了（设备全掉线）。这时 ds 落后于 raw 是正常的，不是 task 的问题
            lastVerdict = "ingestion-stopped";
            return;
        }
        if (dsLatest > 0 && rawLatest - dsLatest <= lagThreshold()) {
            if (!downsampleHealthy) {
                recoverDownsample(now);
            }
            lastTaskSuccessAt = now;
            lastVerdict = "ok";
            return;
        }
        lastVerdict = "stale";
        markDownsampleStale(now);
    }

    private long lagThreshold() {
        return taskLagThresholdMs > 0 ? taskLagThresholdMs : DEFAULT_TASK_LAG_MS;
    }

    private void markDownsampleStale(long now) {
        if (taskOutageStart == 0) {
            taskOutageStart = now;
        }
        staleProbes.incrementAndGet();
        if (downsampleHealthy) {
            downsampleHealthy = false;
            log.error("降采样任务疑似停摆：原始库仍在写入，但 agri-1m 已超过 {} 分钟没有新点。" +
                            "继续下去 raw 的 {} 天 retention 会把未降采样的数据淘汰掉，且查询端不会有任何报错。",
                    lagThreshold() / 60_000, rawRetentionDays);
        }
        // 先止血：把 raw retention 拉长，保证修好 task 之前数据不会蒸发
        rescueRawRetention();
    }

    /** 把原始库 retention 临时拉长到抢救值。幂等，失败会重试（每轮探测都会再试一次） */
    private void rescueRawRetention() {
        if (retentionRescued) {
            return;
        }
        if (rawRetentionDays <= 0) {
            // raw 配的是「永久保留」，本来就不会淘汰任何东西，没有需要抢救的对象。
            // 这里必须提前返回：否则 applyRetention(rawBucket, 365) 会把「无限」改成 365 天，
            // 等于用一次"抢救"把一个无限保留的库缩成有限——反向删数据。
            retentionRescued = true;
            return;
        }
        int days = rescueRetentionDays > 0 ? rescueRetentionDays : DEFAULT_RESCUE_DAYS;
        if (days <= rawRetentionDays) {
            // 配置的保留期本来就比抢救值长（或相同），没有可抢救的空间
            retentionRescued = true;
            return;
        }
        try {
            applyRetention(rawBucket, days);
            retentionRescued = true;
            log.warn("已把原始库 {} 的 retention 从 {} 天临时拉长到 {} 天（降采样停摆期间的抢救动作，" +
                            "补算完成后会自动调回）", rawBucket, rawRetentionDays, days);
        } catch (Exception e) {
            log.error("抢救失败：无法调整原始库 retention，未降采样的数据将在 {} 天后被淘汰: {}",
                    rawRetentionDays, e.getMessage());
        }
    }

    private void recoverDownsample(long now) {
        long start = taskOutageStart;
        downsampleHealthy = true;
        staleProbes.set(0);
        taskOutageStart = 0;

        int backfilled = 0;
        if (start > 0) {
            // task 的 Flux 是 range(start: -task.every)，只算最近 1 分钟，天然不会回补。
            // 所以恢复后空洞不会自己长回来，必须由我们自己补。
            backfilled = backfillDownsample(start, now);
            // 无论补没补齐都要记：补算失败时 ds 库这段就是空的，查询端得知道
            taskGaps.addLast(new long[]{start, now, backfillPending ? 1 : 0});
            while (taskGaps.size() > MAX_GAPS) {
                taskGaps.pollFirst();
            }
        }

        // 只有空洞补齐了才敢把 retention 调回去：否则调回 30 天 = 立刻淘汰那段没降采样的数据
        if (retentionRescued && !backfillPending && rawRetentionDays > 0) {
            try {
                applyRetention(rawBucket, rawRetentionDays);
                retentionRescued = false;
                log.info("降采样已恢复，补算 {} 个点，原始库 retention 调回 {} 天", backfilled, rawRetentionDays);
            } catch (Exception e) {
                log.warn("降采样已恢复，但 retention 回滚失败（继续按抢救值保留，不影响正确性）: {}", e.getMessage());
            }
        }
    }

    /**
     * 把 {@code [from, to]} 这段区间按 1 分钟窗口重新聚合并写入降采样库。
     * 按小时分片，避免一次拉全量。
     *
     * @return 实际写入的点数
     */
    int backfillDownsample(long from, long to) {
        if (to <= from) {
            return 0;
        }
        int maxHours = backfillMaxHours > 0 ? backfillMaxHours : DEFAULT_BACKFILL_MAX_HOURS;
        long maxSpan = maxHours * 3_600_000L;
        if (to - from > maxSpan) {
            // 空洞太长，全量补算等于用一次巨大的查询把自己打死。只补最近一段，
            // 剩下的靠「retention 停在抢救值」兜住，并在 health 里明说没补齐。
            backfillPending = true;
            long truncated = to - maxSpan;
            log.error("降采样空洞 {} 小时超过补算上限 {} 小时：只补最近 {} 小时，" +
                            "更早的数据保留在原始库（retention 维持抢救值），需要人工降采样",
                    (to - from) / 3_600_000, maxHours, maxHours);
            from = truncated;
        }
        int total = 0;
        long cursor = from;
        while (cursor < to) {
            long chunkEnd = Math.min(cursor + BACKFILL_CHUNK_MS, to);
            try {
                total += writeDownsampleChunk(cursor, chunkEnd);
            } catch (Exception e) {
                // 补算失败不阻断：retention 已经拉长，下一轮探测会再判一次停摆并重试
                backfillPending = true;
                log.warn("补算区间 [{}, {}] 失败，保留抢救 retention 待重试: {}",
                        Instant.ofEpochMilli(cursor), Instant.ofEpochMilli(chunkEnd), e.getMessage());
                return total;
            }
            cursor = chunkEnd;
        }
        return total;
    }

    private int writeDownsampleChunk(long from, long to) {
        String flux = String.format(
                "from(bucket: \"%s\")\n" +
                "  |> range(start: %s, stop: %s)\n" +
                "  |> filter(fn: (r) => r._measurement == \"telemetry\")\n" +
                "  |> aggregateWindow(every: 1m, fn: mean, createEmpty: false)\n" +
                "  |> filter(fn: (r) => exists r._value)",
                rawBucket, Instant.ofEpochMilli(from).toString(), Instant.ofEpochMilli(to).toString());
        List<FluxTable> tables = client.getQueryApi().query(flux, org);
        int written = 0;
        List<Point> batch = new ArrayList<>(WRITE_BATCH_SIZE);
        for (FluxTable table : tables) {
            for (FluxRecord r : table.getRecords()) {
                Object device = r.getValueByKey("device");
                String field = r.getField();
                Object value = r.getValue();
                if (device == null || field == null || !(value instanceof Number n)) {
                    continue;
                }
                batch.add(Point.measurement("telemetry_1m")
                        .addTag("device", String.valueOf(device))
                        .addField(field, n.doubleValue())
                        .time(r.getTime(), WritePrecision.NS));
                if (batch.size() >= WRITE_BATCH_SIZE) {
                    writeApi.writePoints(dsBucket, org, batch);
                    written += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            writeApi.writePoints(dsBucket, org, batch);
            written += batch.size();
        }
        return written;
    }

    /** 查某个 bucket 某 measurement 的最新点时间戳；查不到返回 0 */
    private long latestPointTime(String bucket, String measurement) {
        String flux = String.format(
                "from(bucket: \"%s\")\n" +
                "  |> range(start: -2h)\n" +
                "  |> filter(fn: (r) => r._measurement == \"%s\")\n" +
                "  |> group()\n" +
                "  |> last()\n" +
                "  |> keep(columns: [\"_time\"])",
                bucket, measurement);
        try {
            List<FluxTable> tables = client.getQueryApi().query(flux, org);
            long latest = 0;
            for (FluxTable table : tables) {
                for (FluxRecord r : table.getRecords()) {
                    if (r.getTime() != null) {
                        latest = Math.max(latest, r.getTime().toEpochMilli());
                    }
                }
            }
            return latest;
        } catch (Exception e) {
            log.debug("查询 {} 最新点失败: {}", bucket, e.getMessage());
            return 0;
        }
    }

    private void applyRetention(String bucketName, int days) {
        Bucket bucket = client.getBucketsApi().findBucketByName(bucketName);
        if (bucket == null) {
            throw new IllegalStateException("bucket 不存在: " + bucketName);
        }
        bucket.setRetentionRules(days > 0
                ? java.util.List.of(new com.influxdb.client.domain.BucketRetentionRules().everySeconds(days * 86400))
                : java.util.List.of());
        client.getBucketsApi().updateBucket(bucket);
    }

    /** 降采样层健康状况：health 里直接给结论，不让运维自己拼 */
    public Map<String, Object> downsampleHealth() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean checked = lastTaskCheckAt > 0;
        // 三态：unknown（还没探过）/ healthy / stale。false 才报警，null 只表示未知
        m.put("status", lastVerdict);
        m.put("healthy", checked ? downsampleHealthy : null);
        m.put("lastTaskSuccessAt", lastTaskSuccessAt > 0 ? lastTaskSuccessAt : null);
        m.put("lastCheckAt", checked ? lastTaskCheckAt : null);
        m.put("staleProbes", staleProbes.get());
        m.put("lagThresholdMs", lagThreshold());
        // 抢救态必须暴露：此时磁盘增长按 365 天算，不是 30 天
        m.put("retentionDays", retentionRescued
                ? (rescueRetentionDays > 0 ? rescueRetentionDays : DEFAULT_RESCUE_DAYS)
                : rawRetentionDays);
        m.put("retentionRescued", retentionRescued);
        m.put("backfillPending", backfillPending);
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (long[] g : taskGaps) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("start", g[0]);
            gm.put("end", g[1]);
            // backfilled=false 表示这段区间在降采样库里是永久空洞，长周期查询要么避开、要么回原始库
            gm.put("backfilled", g.length > 2 && g[2] == 0);
            gaps.add(gm);
        }
        m.put("gaps", gaps);
        return m;
    }

    private void ensureBucket(String name) {
        boolean exists = client.getBucketsApi().findBuckets().stream()
                .anyMatch(b -> b.getName().equals(name));
        if (!exists) {
            Organization organization = client.getOrganizationsApi().findOrganizations().stream()
                    .filter(o -> org.equals(o.getName())).findFirst()
                    .orElseThrow(() -> new RuntimeException("未找到组织: " + org));
            Bucket bucket = new Bucket();
            bucket.setName(name);
            bucket.setOrgID(organization.getId());
            // 建库时必须带 retention，否则默认 infinite：500 设备 × 2s × 6 指标 ≈ 每天 1300 万点，永久堆积。
            // 原始层与降采样层给不同生命周期，是"数据分层"的一部分，不只是清理策略。
            int days = name.equals(dsBucket) ? dsRetentionDays : rawRetentionDays;
            if (days > 0) {
                bucket.setRetentionRules(java.util.List.of(
                        new com.influxdb.client.domain.BucketRetentionRules().everySeconds(days * 86400)));
            }
            client.getBucketsApi().createBucket(bucket);
            log.info("已创建 bucket: {}（retention={} 天）", name, days > 0 ? days : "永久");
        }
    }

    /** 1 分钟降采样任务：原始数据按设备/字段聚合均值，写入降采样库 */
    private void ensureDownsampleTask() {
        String taskName = "agri-downsample-1m";
        boolean exists = client.getTasksApi().findTasks().stream()
                .anyMatch(t -> taskName.equals(t.getName()));
        if (exists) {
            return;
        }
        String flux = String.format(
                "option task = {name: \"%s\", every: 1m}\n" +
                "from(bucket: \"%s\")\n" +
                "  |> range(start: -task.every)\n" +
                "  |> filter(fn: (r) => r._measurement == \"telemetry\")\n" +
                "  |> aggregateWindow(every: 1m, fn: mean, createEmpty: false)\n" +
                "  |> set(key: \"_measurement\", value: \"telemetry_1m\")\n" +
                "  |> to(bucket: \"%s\", org: \"%s\")",
                taskName, rawBucket, dsBucket, org);
        Organization organization = client.getOrganizationsApi().findOrganizations().stream()
                .filter(o -> org.equals(o.getName())).findFirst()
                .orElseThrow(() -> new RuntimeException("未找到组织: " + org));
        Task task = new Task();
        task.setName(taskName);
        task.setOrgID(organization.getId());
        task.setFlux(flux);
        client.getTasksApi().createTask(task);
        log.info("已创建降采样任务: {}", taskName);
    }

    /**
     * 写入一条遥测。
     *
     * <p>正常态：只入攒批队列就返回，<b>调用方不等网络</b>。队列满则丢弃计数，不阻塞——
     * 阻塞的调用方是 storage 消费者线程，堵它会顺着有界队列一路反压到接入层。
     * 降级态：直接落内存缓冲，等恢复后补写。</p>
     */
    public void write(Telemetry t) {
        if (influxAvailable) {
            if (batchWriter != null) {
                batchWriter.offer(t);
            } else {
                // init 没走过（单测直接 new）或已停机：同步写，保持旧行为
                writeNow(t);
            }
            return;
        }
        stashToMemory(t);
    }

    /** 不经攒批的同步单点写；只在攒批器不可用时兜底 */
    private void writeNow(Telemetry t) {
        try {
            synchronized (writeLock) {
                writeApi.writePoints(rawBucket, org, List.of(toPoint(t)));
            }
            consecutiveWriteFailures.set(0);
        } catch (Exception e) {
            onTransportFailure(List.of(t), e);
        }
    }

    /**
     * 攒批器的落库回调：由后台单线程调用，一次处理一批遥测。
     *
     * <p><b>这里的错误分类是整个降级逻辑的关键。</b>上一版是
     * {@code catch (Exception e) → influxAvailable = false}，把「网络不通」和
     * 「这条数据服务端不要」当成同一件事。后果有两个：</p>
     * <ol>
     *   <li>网络抖一次，几百台设备全部切进内存，哪怕第二秒就恢复了；</li>
     *   <li>字段类型冲突这类 HTTP 400 会让系统<b>永久</b>留在降级态——重试永远是 400，
     *       于是正常数据也开始往内存里堆，最终再次 OOM。</li>
     * </ol>
     */
    private boolean sinkWriteBatch(List<Telemetry> batch) {
        if (!influxAvailable) {
            batch.forEach(this::stashToMemory);
            return true;
        }
        List<Point> points = new ArrayList<>(batch.size());
        for (Telemetry t : batch) {
            Point p = toPoint(t);
            // 没有任何有效字段的点写进去必被服务端拒（400），而攒批之后一条脏数据
            // 会连累另外几百条好数据一起被退回——批量把单点故障放大了 500 倍，
            // 所以必须在组批阶段就把空点剔掉。
            if (p != null) {
                points.add(p);
            }
        }
        if (points.isEmpty()) {
            return true;
        }
        try {
            synchronized (writeLock) {
                writeApi.writePoints(rawBucket, org, points);
            }
            consecutiveWriteFailures.set(0);
            return true;
        } catch (InfluxException e) {
            int status = e.status();
            // 4xx = 请求或数据本身有问题。408（超时）与 429（限流）属于「过会儿再来」，
            // 不算不可重试；其余 4xx（400 行协议/字段类型、401、413 过大）重试结果一样。
            if (status >= 400 && status < 500 && status != 408 && status != 429) {
                rejectedPoints.addAndGet(points.size());
                log.warn("InfluxDB 拒绝写入（HTTP {}），{} 个点按不可重试丢弃，不触发降级。"
                                + "先查上报字段类型是否与前序数据冲突: {}",
                        status, points.size(), e.getMessage());
                return true;
            }
            return onTransportFailure(batch, e);
        } catch (Exception e) {
            return onTransportFailure(batch, e);
        }
    }

    /**
     * 传输层失败：数据先转进内存不丢，<b>连续失败到阈值才整体降级</b>。
     *
     * @return 恒为 false，表示这批没写成功
     */
    private boolean onTransportFailure(List<Telemetry> batch, Exception e) {
        batch.forEach(this::stashToMemory);
        int fails = consecutiveWriteFailures.incrementAndGet();
        if (fails >= Math.max(1, writeFailThreshold)) {
            boolean wasAvailable = influxAvailable;
            influxAvailable = false;
            enterDegraded("write-failed");
            if (wasAvailable) {
                log.warn("InfluxDB 连续 {} 批写入失败，切换为内存降级（数据已转内存，恢复后补写）: {}",
                        fails, e.getMessage());
            }
        } else {
            log.debug("InfluxDB 写入失败（第 {}/{} 次，未达降级阈值），本批 {} 条已转内存待补写: {}",
                    fails, Math.max(1, writeFailThreshold), batch.size(), e.getMessage());
        }
        return false;
    }

    /** 遥测 → 写入点；字段全部无效时返回 null（调用方据此跳过） */
    private Point toPoint(Telemetry t) {
        Point point = Point.measurement("telemetry")
                .addTag("device", t.getDeviceId())
                .time(Instant.ofEpochMilli(t.getTs()), WritePrecision.MS);
        int valid = 0;
        for (Map.Entry<String, Double> e : t.getMetrics().entrySet()) {
            double v = e.getValue() == null ? Double.NaN : e.getValue();
            // NaN / ±Infinity 在行协议里是非法的，服务端整批 400。
            // 这里逐字段剔除，让一条坏读数只丢它自己，不连累同批的几百条。
            if (!Double.isFinite(v)) {
                invalidFields.incrementAndGet();
                continue;
            }
            point.addField(e.getKey(), v);
            valid++;
        }
        return valid == 0 ? null : point;
    }

    /** 落进内存降级缓冲（每设备有界淘汰）；写入失败与整体降级共用这一条路 */
    private void stashToMemory(Telemetry t) {
        if (t == null || t.getMetrics() == null) {
            return;
        }
        List<Rec> list = memRaw.computeIfAbsent(t.getDeviceId(), k -> Collections.synchronizedList(new ArrayList<>()));
        for (Map.Entry<String, Double> e : t.getMetrics().entrySet()) {
            if (e.getValue() == null || !Double.isFinite(e.getValue())) {
                continue;
            }
            list.add(new Rec(t.getDeviceId(), t.getTs(), e.getKey(), e.getValue()));
        }
        // 有界淘汰：仅保留每设备最近 MAX_MEM_POINTS_PER_DEVICE 个点，避免 1000 设备长跑时无限堆积导致 OOM
        synchronized (list) {
            int over = list.size() - MAX_MEM_POINTS_PER_DEVICE;
            if (over > 0) {
                list.subList(0, over).clear();
                // 记下来：这些点已经永久丢失，补写也救不回，必须让查询端知道这段不可信
                evictedCounter.addAndGet(over);
            }
        }
    }

    /** 从原始库按时间范围查询某设备某指标 */
    public List<Map<String, Object>> queryRaw(String deviceId, String metric,
                                              String start, String stop) {
        if (influxAvailable) {
            String flux = String.format(
                    "from(bucket: \"%s\")\n" +
                    "  |> range(start: %s, stop: %s)\n" +
                    "  |> filter(fn: (r) => r._measurement == \"telemetry\" and r.device == \"%s\" and r._field == \"%s\")\n" +
                    "  |> sort(columns: [\"_time\"])",
                    rawBucket, start, stop, deviceId, metric);
            return runQuery(flux);
        }
        return memRawQuery(deviceId, metric, start, stop, false);
    }

    /** 从降采样库查询（长周期聚合，速度快） */
    public List<Map<String, Object>> queryDownsampled(String deviceId, String metric,
                                                      String start, String stop) {
        if (influxAvailable) {
            String flux = String.format(
                    "from(bucket: \"%s\")\n" +
                    "  |> range(start: %s, stop: %s)\n" +
                    "  |> filter(fn: (r) => r._measurement == \"telemetry_1m\" and r.device == \"%s\" and r._field == \"%s\")\n" +
                    "  |> sort(columns: [\"_time\"])",
                    dsBucket, start, stop, deviceId, metric);
            return runQuery(flux);
        }
        return memRawQuery(deviceId, metric, start, stop, true);
    }

    /** 长周期聚合（按小时窗口），演示降采样优化后的低延迟聚合 */
    public List<Map<String, Object>> queryLongPeriodAgg(String deviceId, String metric,
                                                        String start, String stop) {
        if (influxAvailable) {
            String flux = String.format(
                    "from(bucket: \"%s\")\n" +
                    "  |> range(start: %s, stop: %s)\n" +
                    "  |> filter(fn: (r) => r._measurement == \"telemetry_1m\" and r.device == \"%s\" and r._field == \"%s\")\n" +
                    "  |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)\n" +
                    "  |> sort(columns: [\"_time\"])",
                    dsBucket, start, stop, deviceId, metric);
            return runQuery(flux);
        }
        return memRawQuery(deviceId, metric, start, stop, true);
    }

    private List<Map<String, Object>> runQuery(String flux) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<FluxTable> tables = client.getQueryApi().query(flux, org);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("time", record.getTime() != null ? record.getTime().toString() : null);
                    row.put("value", record.getValue());
                    result.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("InfluxDB 查询失败: {}", e.getMessage());
        }
        return result;
    }

    // ===================== 内存降级实现 =====================

    /** 解析 Flux 风格的时间范围（-1h / -24h / -7d / now()）为毫秒区间 */
    private long[] parseRange(String start, String stop) {
        long now = System.currentTimeMillis();
        long stopMs = now;
        if (stop != null && !stop.isBlank() && !"now()".equals(stop)) {
            stopMs = resolveOffset(now, stop);
        }
        long startMs = now - 3_600_000L; // 默认 1h
        if (start != null && !start.isBlank() && !"now()".equals(start)) {
            startMs = resolveOffset(now, start);
        }
        return new long[]{startMs, stopMs};
    }

    private long resolveOffset(long now, String s) {
        // 形如 -1h / -24h / -7d / -30m
        if (s.startsWith("-")) {
            String body = s.substring(1);
            char unit = body.charAt(body.length() - 1);
            String num = body.substring(0, body.length() - 1);
            try {
                long v = Long.parseLong(num);
                return switch (unit) {
                    case 'm' -> now - v * 60_000L;
                    case 'h' -> now - v * 3_600_000L;
                    case 'd' -> now - v * 86_400_000L;
                    default -> now;
                };
            } catch (NumberFormatException ignored) {
                return now;
            }
        }
        return now;
    }

    /** 内存降级查询：raw=false 返回原始采样；raw=true 返回按分钟聚合的降采样点 */
    private List<Map<String, Object>> memRawQuery(String deviceId, String metric,
                                                  String start, String stop, boolean downsample) {
        long[] range = parseRange(start, stop);
        List<Map<String, Object>> out = new ArrayList<>();
        List<Rec> recs = memRaw.get(deviceId);
        if (recs == null) {
            return out;
        }
        // 聚合：窗口 = 1 分钟（降采样）或 原始点
        Map<Long, double[]> agg = new LinkedHashMap<>();
        for (Rec r : recs) {
            if (!metric.equals(r.field)) {
                continue;
            }
            if (r.ts < range[0] || r.ts > range[1]) {
                continue;
            }
            if (downsample) {
                long bucket = (r.ts / 60_000L) * 60_000L;
                double[] s = agg.computeIfAbsent(bucket, k -> new double[]{0, 0});
                s[0] += r.value;
                s[1] += 1;
            } else {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", Instant.ofEpochMilli(r.ts).toString());
                row.put("value", r.value);
                out.add(row);
            }
        }
        if (downsample) {
            for (Map.Entry<Long, double[]> e : agg.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", Instant.ofEpochMilli(e.getKey()).toString());
                row.put("value", e.getValue()[0] / e.getValue()[1]);
                out.add(row);
            }
        }
        return out;
    }

    public boolean isInfluxAvailable() {
        return influxAvailable;
    }

    /** 内存降级存储的单条采样记录 */
    private static final class Rec {
        final String device;
        final long ts;
        final String field;
        final double value;

        Rec(String device, long ts, String field, double value) {
            this.device = device;
            this.ts = ts;
            this.field = field;
            this.value = value;
        }
    }
}
