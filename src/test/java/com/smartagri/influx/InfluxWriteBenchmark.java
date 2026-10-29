package com.smartagri.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.smartagri.device.Telemetry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 写入路径基准：攒批开 / 攒批关，同一份负载下对比吞吐与落库延迟。
 *
 * <p><b>这不是单元测试</b>（类名不以 Test 结尾，surefire 不会捡它），也不进主流程。
 * 存在的理由只有一个：{@code InfluxBatchWriter} 的收益此前只有一个实测数字
 * （9,210 点压成 113 次请求），<b>延迟和吞吐从来没测过</b>。面试里被问
 * 「你凭什么说攒批值得」，只有请求数一项是站不住的。</p>
 *
 * <p><b>两条路径走的是同一份生产代码。</b>不复制 {@code toPoint()}、不复制错误分类，
 * 靠 {@code InfluxService.write()} 内部本来就有的分叉：</p>
 * <ul>
 *   <li>{@code batchWriter == null} → {@code writeNow()} 逐条同步写（攒批关，即改动前的行为）</li>
 *   <li>{@code batchWriter != null} → {@code offer()} 入队，后台线程攒批写（攒批开）</li>
 * </ul>
 *
 * <p><b>延迟的起点是「这条遥测被生产出来」，终点是「HTTP 返回 2xx」。</b>
 * 所以攒批路径的延迟天然包含在队列里等 tick 的时间——那正是要测的代价。</p>
 *
 * <p>跑法见 {@code scripts/bench-write.sh}。</p>
 */
public final class InfluxWriteBenchmark {

    /** 延迟样本上限。压满场景关掉采样，只有稳态场景才记，所以 200 万够用 */
    private static final int MAX_SAMPLES = 2_000_000;

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        String path = a.getOrDefault("path", "batch");        // perpoint | batch
        int delayMs = intOf(a, "delay", 8);                   // 服务端注入的处理耗时
        int rate = intOf(a, "rate", 100);                     // 目标总速率（点/秒），0 = 不限速
        int seconds = intOf(a, "seconds", 30);
        int threads = intOf(a, "threads", 6);                 // 生产线程数（生产环境是 6 个 storage 消费者）
        int devices = intOf(a, "devices", 200);
        int queueCap = intOf(a, "queue", 20000);
        boolean collectLatency = !"false".equals(a.getOrDefault("latency", "true"));
        String url = a.getOrDefault("url", "http://127.0.0.1:8086");

        // 服务端注入延迟 + 清零统计（每组实验从零算）
        get(url + "/_mode?m=ok&d=" + delayMs + "&reset=1");

        InfluxDBClient client = InfluxDBClientFactory.create(url, "dev-token".toCharArray(),
                "smart-agri", "agri-raw");
        InfluxService svc = new InfluxService(client);
        // 绕开 Spring 注入：这些字段平时由 @Value 填，直接 new 时是 null
        set(svc, "org", "smart-agri");
        set(svc, "rawBucket", "agri-raw");
        set(svc, "dsBucket", "agri-1m");
        set(svc, "writeApi", client.getWriteApiBlocking());
        set(svc, "influxAvailable", true);

        Map<Long, Long> enqNanos = collectLatency ? new ConcurrentHashMap<>() : null;
        long[] latencies = collectLatency ? new long[MAX_SAMPLES] : null;
        AtomicInteger latIdx = new AtomicInteger();

        InfluxBatchWriter writer = null;
        if ("batch".equals(path)) {
            Method realSink = InfluxService.class.getDeclaredMethod("sinkWriteBatch", List.class);
            realSink.setAccessible(true);
            set(svc, "writeBatchSize", 500);
            InfluxBatchWriter.Sink timingSink = batch -> {
                Boolean ok;
                try {
                    ok = (Boolean) realSink.invoke(svc, batch);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
                if (enqNanos != null) {
                    long done = System.nanoTime();
                    for (Telemetry t : batch) {
                        Long e0 = enqNanos.get(t.getTs());
                        if (e0 == null) {
                            continue;
                        }
                        enqNanos.remove(t.getTs());
                        int i = latIdx.getAndIncrement();
                        if (i < MAX_SAMPLES) {
                            latencies[i] = done - e0;
                        }
                    }
                }
                return ok;
            };
            writer = new InfluxBatchWriter(queueCap, 500, 200, timingSink);
            set(svc, "batchWriter", writer);
        }

        // ===== 生产者 =====
        final long baseTs = 1_700_000_000_000L;
        AtomicLong seq = new AtomicLong();
        AtomicLong produced = new AtomicLong();
        long startAt = System.nanoTime();
        long deadline = startAt + seconds * 1_000_000_000L;

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> producers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final InfluxBatchWriter w = writer;
            final long intervalNanos = rate > 0 ? (1_000_000_000L * threads) / rate : 0L;
            Thread th = new Thread(() -> {
                long next = System.nanoTime();
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ie) {
                    return;
                }
                while (System.nanoTime() < deadline) {
                    if (intervalNanos > 0) {
                        next += intervalNanos;
                        long wait = next - System.nanoTime();
                        if (wait > 0) {
                            LockSupport.parkNanos(wait);
                        } else {
                            next = System.nanoTime();   // 落后了就别追，避免补发风暴
                        }
                    }
                    long s = seq.getAndIncrement();
                    long ts = baseTs + s;
                    Telemetry t = Telemetry.builder()
                            .deviceId("dev-" + (s % devices))
                            .ts(ts)
                            .build();
                    // 一条遥测 4 个字段，跟真实上报的量级一致
                    t.putMetric("temperature", 20.0 + (s % 100) / 10.0);
                    t.putMetric("humidity", 55.0 + (s % 70) / 10.0);
                    t.putMetric("soil_moisture", 30.0 + (s % 50) / 10.0);
                    t.putMetric("light", 800.0 + (s % 400));
                    if (enqNanos != null) {
                        enqNanos.put(ts, System.nanoTime());
                    }
                    if (w != null) {
                        w.offer(t);
                    } else {
                        svc.write(t);           // 逐条路径：同步阻塞，等 HTTP 返回
                        if (enqNanos != null) {
                            Long e0 = enqNanos.remove(ts);
                            if (e0 != null) {
                                int slot = latIdx.getAndIncrement();
                                if (slot < MAX_SAMPLES) {
                                    latencies[slot] = System.nanoTime() - e0;
                                }
                            }
                        }
                    }
                    produced.incrementAndGet();
                }
            }, "bench-producer-" + i);
            th.setDaemon(true);
            producers.add(th);
            th.start();
        }
        ready.await();
        go.countDown();
        for (Thread th : producers) {
            th.join();
        }
        long elapsedNanos = System.nanoTime() - startAt;

        // 攒批：等队列排空（最多 5 秒），否则最后一批的点没有延迟样本
        if (writer != null) {
            long waitUntil = System.nanoTime() + 5_000_000_000L;
            while (writer.pending() > 0 && System.nanoTime() < waitUntil) {
                LockSupport.parkNanos(20_000_000L);
            }
        }
        double elapsedS = elapsedNanos / 1e9;

        // ===== 服务端侧统计（吞吐以「真的落库了多少点」为准，不以投递数为准）=====
        String statsJson = get(url + "/_stats");
        long serverPoints = longField(statsJson, "points_total");
        long serverCalls = longField(statsJson, "write_calls");

        System.out.println();
        System.out.println("========== 写入路径基准 ==========");
        System.out.println("路径          : " + ("batch".equals(path) ? "攒批开（200ms/500 条）" : "攒批关（每条一次 HTTP，带 writeLock）"));
        System.out.println("服务端注入延迟: " + delayMs + " ms/次");
        System.out.println("目标速率      : " + (rate > 0 ? rate + " 点/秒" : "不限速") + "，" + threads + " 个生产线程");
        System.out.println("设备数        : " + devices);
        System.out.println("时长          : " + String.format("%.2f", elapsedS) + " s");
        System.out.println("----------------------------------");
        System.out.println("投递条数      : " + produced.get());
        System.out.println("实际投递速率  : " + String.format("%.1f", produced.get() / elapsedS) + " 点/秒"
                + (rate > 0 ? String.format("（目标 %d，达成 %.0f%%）", rate, 100.0 * (produced.get() / elapsedS) / rate) : ""));
        System.out.println("落库点数      : " + serverPoints + "（丢弃 "
                + (writer != null ? writer.droppedCount() : 0) + "）");
        System.out.println("落库吞吐      : " + String.format("%.1f", serverPoints / elapsedS) + " 点/秒");
        System.out.println("HTTP 请求数   : " + serverCalls);
        System.out.println("平均点数/请求 : " + String.format("%.1f", serverCalls == 0 ? 0 : (double) serverPoints / serverCalls));
        if (collectLatency) {
            int n = Math.min(latIdx.get(), MAX_SAMPLES);
            long[] v = Arrays.copyOf(latencies, n);
            Arrays.sort(v);
            System.out.println("----------------------------------");
            System.out.println("延迟样本      : " + n + (latIdx.get() > MAX_SAMPLES ? "（已截断）" : ""));
            System.out.println("P50           : " + String.format("%.1f ms", pct(v, 50) / 1e6));
            System.out.println("P95           : " + String.format("%.1f ms", pct(v, 95) / 1e6));
            System.out.println("P99           : " + String.format("%.1f ms", pct(v, 99) / 1e6));
            System.out.println("MAX           : " + String.format("%.1f ms", pct(v, 100) / 1e6));
        }
        if (writer != null) {
            System.out.println("----------------------------------");
            System.out.println("accepted      : " + writer.acceptedCount());
            System.out.println("written       : " + writer.writtenCount());
            System.out.println("failedBatches : " + writer.failedBatches());
        }
        System.out.println("==================================");

        if (writer != null) {
            writer.shutdown();
        }
        client.close();
        System.exit(0);
    }

    // ===== 小工具 =====

    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String s : args) {
            if (s.startsWith("--") && s.contains("=")) {
                m.put(s.substring(2, s.indexOf('=')), s.substring(s.indexOf('=') + 1));
            }
        }
        return m;
    }

    private static int intOf(Map<String, String> m, String k, int dflt) {
        return m.containsKey(k) ? Integer.parseInt(m.get(k)) : dflt;
    }

    private static String get(String url) throws Exception {
        HttpClient c = HttpClient.newHttpClient();
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    /** 从扁平 JSON 里抠一个整数字段（不引第三方解析器，够用就行） */
    private static long longField(String json, String name) {
        int i = json.indexOf("\"" + name + "\"");
        if (i < 0) {
            return -1;
        }
        int colon = json.indexOf(':', i);
        int end = colon + 1;
        while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        int e = end;
        while (e < json.length() && "-0123456789".indexOf(json.charAt(e)) >= 0) {
            e++;
        }
        return Long.parseLong(json.substring(end, e));
    }

    private InfluxWriteBenchmark() {
    }
}
