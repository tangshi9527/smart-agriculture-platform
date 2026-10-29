package com.smartagri.device;

import com.smartagri.common.Constants;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 设备影子：以 Hash 存储设备最新状态与读数，读取是一次 HGET。
 * 离线判定由定时任务完成，探测周期默认 1s，因此判定延迟约等于一个扫描周期。
 * <p>
 * <b>在线集合是 ZSET 不是 SET</b>（member=deviceId，score=最后一次心跳的毫秒时间戳）。
 * 这样"谁超时了"是一次 {@code ZRANGEBYSCORE} 就能回答的问题，不需要把在线设备逐个
 * HGET 回来比较——见 {@link #scanOffline()} 的说明。
 * <p>
 * 容错：若 Redis 不可用（例如本地未启动 docker-compose），自动降级为进程内内存影子，
 * 设备状态读取、离线判定、WebSocket 在线数推送仍可正常工作，服务不依赖外部组件即可启动。
 */
@Service
public class DeviceShadowService {

    private final StringRedisTemplate redis;

    /** 心跳超时阈值（毫秒）：超过即判定离线 */
    private static final long HEARTBEAT_TIMEOUT_MS = 10_000;

    /** SCAN 每次建议返回的 key 数量：太小往返多，太大单次阻塞久 */
    private static final long SCAN_COUNT = 200;

    /** 内存降级下心跳字段被写坏的次数：脏数据只跳过该设备，不降级 */
    private final AtomicLong badHeartbeats = new AtomicLong();

    /** Redis 是否可用；失败后进入内存降级，但会由 {@link #probeRedis()} 定期探测恢复 */
    private volatile boolean useRedis = true;

    /** 内存降级：设备影子(device -> 字段映射) */
    private final ConcurrentHashMap<String, Map<String, String>> memShadows = new ConcurrentHashMap<>();
    /** 内存降级：在线设备集合 */
    private final Set<String> memOnline = ConcurrentHashMap.newKeySet();
    /** 内存降级：出现过的全部设备（只增不删，对应 {@link Constants#DEVICES_SET}） */
    private final Set<String> memDevices = ConcurrentHashMap.newKeySet();

    /** 下次探测恢复的最早时间与连续失败次数：指数退避，避免「切回→打满→再降级」的尖峰 */
    private volatile long nextProbeAt = 0;
    private final java.util.concurrent.atomic.AtomicInteger recoverAttempts = new java.util.concurrent.atomic.AtomicInteger(0);

    @org.springframework.beans.factory.annotation.Value("${redis.recover-probe-ms:30000}")
    private long recoverBaseMs;

    public DeviceShadowService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void markOnline(String deviceId, Telemetry t) {
        String key = Constants.SHADOW_PREFIX + deviceId;
        long now = System.currentTimeMillis();
        if (useRedis) {
            try {
                var h = redis.opsForHash();
                h.put(key, "deviceId", deviceId);
                h.put(key, "status", "online");
                h.put(key, "lastHeartbeat", String.valueOf(now));
                if (t != null) {
                    h.put(key, "ts", String.valueOf(t.getTs()));
                    for (Map.Entry<String, Double> e : t.getMetrics().entrySet()) {
                        h.put(key, e.getKey(), String.valueOf(e.getValue()));
                    }
                }
                // 同一次调用里顺手把心跳时间写进 ZSET 的 score：
                // 上线判定与心跳记录合成一步，不用额外往返
                redis.opsForZSet().add(Constants.ONLINE_ZSET, deviceId, now);
                // 只增不删：离线后仍算"这台设备存在过"，否则总数会随下线掉到 0
                redis.opsForSet().add(Constants.DEVICES_SET, deviceId);
                return;
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
        // 内存降级
        Map<String, String> m = memShadows.computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>());
        m.put("deviceId", deviceId);
        m.put("status", "online");
        m.put("lastHeartbeat", String.valueOf(now));
        if (t != null) {
            m.put("ts", String.valueOf(t.getTs()));
            for (Map.Entry<String, Double> e : t.getMetrics().entrySet()) {
                m.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        memOnline.add(deviceId);
        memDevices.add(deviceId);
    }

    public void markOffline(String deviceId) {
        String key = Constants.SHADOW_PREFIX + deviceId;
        if (useRedis) {
            try {
                redis.opsForHash().put(key, "status", "offline");
                redis.opsForZSet().remove(Constants.ONLINE_ZSET, deviceId);
                return;
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
        Map<String, String> m = memShadows.get(deviceId);
        if (m != null) {
            m.put("status", "offline");
        }
        memOnline.remove(deviceId);
    }

    public Map<Object, Object> getShadow(String deviceId) {
        String key = Constants.SHADOW_PREFIX + deviceId;
        if (useRedis) {
            try {
                return redis.opsForHash().entries(key);
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
        Map<String, String> m = memShadows.get(deviceId);
        return m == null ? new HashMap<>() : new HashMap<>(m);
    }

    public List<Map<Object, Object>> getAllShadows() {
        List<Map<Object, Object>> result = new ArrayList<>();
        if (useRedis) {
            try {
                // 用 SCAN 游标替代 KEYS：KEYS 的时间复杂度是 O(N) 且会阻塞 Redis 单线程，
                // 在几百个 key 上看不出问题，但它是那种"数据一涨就整库卡死"的命令，
                // 属于不该出现在生产路径里的东西。SCAN 是增量游标，每次阻塞可忽略。
                List<String> keys = new ArrayList<>();
                ScanOptions options = ScanOptions.scanOptions()
                        .match(Constants.SHADOW_PREFIX + "*")
                        .count(SCAN_COUNT)
                        .build();
                try (Cursor<String> cursor = redis.scan(options)) {
                    while (cursor.hasNext()) {
                        keys.add(cursor.next());
                    }
                }
                for (String k : keys) {
                    Map<Object, Object> m = redis.opsForHash().entries(k);
                    if (m == null || m.isEmpty()) {
                        continue;
                    }
                    result.add(m);
                }
                return result;
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
        for (Map<String, String> m : memShadows.values()) {
            if (m == null || m.isEmpty()) {
                continue;
            }
            result.add(new HashMap<>(m));
        }
        return result;
    }

    public long onlineCount() {
        if (useRedis) {
            try {
                Long size = redis.opsForZSet().size(Constants.ONLINE_ZSET);
                return size == null ? 0 : size;
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
        return memOnline.size();
    }

    /**
     * 周期扫描：把心跳超时的设备标记为离线。
     *
     * <p><b>为什么改成 ZRANGEBYSCORE。</b>上一版是这样的：{@code SMEMBERS} 拿全部在线设备，
     * 再对每一台 {@code HGET lastHeartbeat} —— 1000 台设备就是每秒 1 + 1000 次 Redis 往返。
     * 本机 loopback 撑得住，真实网络 RTT 1ms 时这个扫描根本追不上心跳，等于用自己的
     * 往返延迟去对抗心跳间隔。心跳时间做成 ZSET 的 score 之后，整个判定压成一次
     * {@code ZRANGEBYSCORE}：正常情况下（没有设备超时）返回空集，一次往返，零额外开销。</p>
     *
     * <p>顺带修掉一个更危险的隐患：上一版 {@code Long.parseLong(hb)} 和 Redis 操作共用
     * 同一个 try，心跳字段被写坏就抛 {@code NumberFormatException}，被
     * {@code catch (Exception)} 当成"Redis 挂了"，于是 {@code useRedis=false}，
     * <b>一台设备的脏字段把全部设备的状态切进内存影子</b>。现在 Redis 路径不再解析字符串
     * （score 是数值），内存路径单独 try 且只跳过该设备。</p>
     */
    @Scheduled(fixedDelay = 1000)
    public void scanOffline() {
        if (useRedis) {
            try {
                long now = System.currentTimeMillis();
                Set<String> expired = redis.opsForZSet()
                        .rangeByScore(Constants.ONLINE_ZSET, 0, now - HEARTBEAT_TIMEOUT_MS);
                if (expired == null || expired.isEmpty()) {
                    return;
                }
                for (String deviceId : expired) {
                    markOffline(deviceId);
                }
                return;
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
        long now = System.currentTimeMillis();
        for (String deviceId : new ArrayList<>(memOnline)) {
            Map<String, String> m = memShadows.get(deviceId);
            if (m == null) {
                memOnline.remove(deviceId);
                continue;
            }
            long hb;
            try {
                hb = Long.parseLong(m.getOrDefault("lastHeartbeat", "0"));
            } catch (NumberFormatException bad) {
                // 单台设备的心跳字段被写坏：只跳过这一台并计数。
                // 绝不能让它把整个扫描打断（上一版的 bug 就是在这里炸的）
                badHeartbeats.incrementAndGet();
                continue;
            }
            if (now - hb > HEARTBEAT_TIMEOUT_MS) {
                markOffline(deviceId);
            }
        }
    }

    public Map<String, Object> summary() {
        Map<String, Object> s = new HashMap<>();
        s.put("online", onlineCount());
        s.put("total", deviceTotal());
        s.put("redisAvailable", useRedis);
        s.put("badHeartbeats", badHeartbeats.get());
        return s;
    }

    /**
     * 设备总数 = 出现过的设备数。
     *
     * <p>上一版是 {@code getAllShadows().size()}——谁打开一次大屏，就做一次全库 KEYS
     * 加逐台 HGETALL。现在是一次 {@code SCARD}，O(1)。</p>
     *
     * <p>为什么不用注册表 {@code DeviceKeyRepository.count()}：模拟器造出来的设备
     * 不在注册表里，用它算总数会出现「总数 10 / 在线 30」这种自相矛盾的界面。
     * "总数"在这里的语义是「平台见过的设备」，和注册表（已授权接入）不是一回事。</p>
     */
    private long deviceTotal() {
        if (useRedis) {
            try {
                Long n = redis.opsForSet().size(Constants.DEVICES_SET);
                return n == null ? 0 : n;
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
        return memDevices.size();
    }

    /**
     * 写入单条控制状态（执行器开关）。值统一存为字符串，便于 Redis/内存共用同一结构。
     * 与遥测指标同存于影子 Hash，互不覆盖，因此控制状态可在多次遥测上报间持久保留。
     */
    public void setControl(String deviceId, String name, Object value) {
        String key = Constants.SHADOW_PREFIX + deviceId;
        String v = value == null ? "0" : String.valueOf(value);
        if (useRedis) {
            try {
                redis.opsForHash().put(key, name, v);
                return;
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
        Map<String, String> m = memShadows.computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>());
        m.put(name, v);
    }

    /**
     * 读取设备执行器状态（fan/valve/lamp/pump），缺失项默认 "0"（关）。
     */
    public Map<String, Object> getControls(String deviceId) {
        String key = Constants.SHADOW_PREFIX + deviceId;
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> names = java.util.Arrays.asList("fan", "valve", "lamp", "pump");
        if (useRedis) {
            try {
                for (String n : names) {
                    Object v = redis.opsForHash().get(key, n);
                    out.put(n, v == null ? "0" : v);
                }
                return out;
            } catch (Exception e) {
                handleRedisFailure(e);
            }
        }
        Map<String, String> m = memShadows.get(deviceId);
        for (String n : names) {
            out.put(n, (m != null && m.containsKey(n)) ? m.get(n) : "0");
        }
        return out;
    }

    public boolean isRedisAvailable() {
        return useRedis;
    }

    /**
     * 降级恢复探测：降级是「单向门」的话，Redis 抖一下就会永久用内存影子——
     * 多实例下各节点影子各算各的、重启即丢，属于明显的设计缺陷。
     *
     * <p>这里每 30s 探一次，Redis 回来就<strong>先把降级期间的内存状态回写</strong>再切回，
     * 否则这期间更新的设备读数会在切回瞬间丢失。</p>
     */
    @Scheduled(fixedDelayString = "${redis.recover-tick-ms:5000}")
    public void probeRedis() {
        if (useRedis) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextProbeAt) {
            return;
        }
        try {
            // 轻量探测：只要这条命令不抛异常就认为 Redis 活了
            redis.hasKey(Constants.ONLINE_ZSET);
        } catch (Exception e) {
            scheduleNextProbe(now); // 仍然不可用，退避后重试
            return;
        }
        try {
            var h = redis.opsForHash();
            int flushed = 0;
            for (Map.Entry<String, Map<String, String>> e : memShadows.entrySet()) {
                Map<String, String> v = e.getValue();
                if (v != null && !v.isEmpty()) {
                    h.putAll(Constants.SHADOW_PREFIX + e.getKey(), new HashMap<>(v));
                    flushed++;
                }
            }
            if (!memOnline.isEmpty()) {
                long hbNow = System.currentTimeMillis();
                Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
                for (String id : memOnline) {
                    tuples.add(ZSetOperations.TypedTuple.of(id, (double) hbNow));
                }
                // 回写心跳时间到 score，避免刚切回就被离线扫描判死
                redis.opsForZSet().add(Constants.ONLINE_ZSET, tuples);
                redis.opsForSet().add(Constants.DEVICES_SET, memDevices.toArray(new String[0]));
                for (String id : memOnline) {
                    h.put(Constants.SHADOW_PREFIX + id, "lastHeartbeat", String.valueOf(hbNow));
                }
            }
            memShadows.clear();
            memOnline.clear();
            memDevices.clear();
            useRedis = true;
            recoverAttempts.set(0);
            nextProbeAt = 0;
            log().info("Redis 已恢复，设备影子切回 Redis，回写 {} 台设备的内存状态", flushed);
        } catch (Exception e) {
            // 回写过程中又断了：保持降级状态，内存数据不清空，下一轮重试
            log().warn("Redis 恢复探测成功但回写失败，继续使用内存影子: {}", e.getMessage());
            scheduleNextProbe(System.currentTimeMillis());
        }
    }

    /** 指数退避：30s → 60s → 120s → 240s → 300s 封顶 */
    private void scheduleNextProbe(long now) {
        int n = recoverAttempts.incrementAndGet();
        long delay = Math.min(recoverBaseMs << Math.min(n - 1, 4), 300_000L);
        nextProbeAt = now + delay;
    }

    public int recoverAttempts() {
        return recoverAttempts.get();
    }

    public long nextProbeAt() {
        return nextProbeAt;
    }

    private static org.slf4j.Logger log() {
        return org.slf4j.LoggerFactory.getLogger(DeviceShadowService.class);
    }

    /**
     * 在线集合从 SET 改成 ZSET 之后的兼容处理。
     *
     * <p>旧版本往 {@code agri:online} 里写的是 SET。升级后第一次 {@code ZADD} 会拿到
     * {@code WRONGTYPE Operation against a key holding the wrong kind of value}，
     * 而外层是 {@code catch (Exception)}——如果不特判，这个类型不匹配会被当成
     * 「Redis 挂了」，把整个设备影子打进内存降级，而且因为探活只能 ping 通、
     * 一写又报 WRONGTYPE，会陷入「降级 → 探活成功 → 回写失败 → 继续降级」的死循环。</p>
     *
     * <p>处理方式是直接删除重建：在线名单本来就是可再生数据，
     * 每台设备的下一次心跳会把它重新写回来。为兼容旧类型写两套读分支不值当。</p>
     *
     * @return true 表示这是类型不匹配且已处理，调用方<b>不应</b>降级
     */
    private boolean resetIfWrongType(Exception e) {
        String msg = e.getMessage();
        if (msg == null || !msg.contains("WRONGTYPE")) {
            return false;
        }
        try {
            redis.delete(Constants.ONLINE_ZSET);
            log().warn("在线集合类型不匹配（旧版本存的是 SET，现按 ZSET 使用），已删除重建");
            return true;
        } catch (Exception ignore) {
            // 连删都删不掉，那就还是当 Redis 不可用，交给探活重试
            return false;
        }
    }

    /**
     * 统一的 Redis 异常处理入口。
     *
     * <p>区分两类：类型不匹配（旧版本把在线集合存成 SET）只删键重建、<b>不降级</b>；
     * 其余才认为是 Redis 真不可用。不区分的话，一次类型冲突会触发「降级 → 探活 ping 通 →
     * 回写又 WRONGTYPE → 继续降级」的死循环，看起来像 Redis 一直挂着。</p>
     */
    private void handleRedisFailure(Exception e) {
        if (resetIfWrongType(e)) {
            return;
        }
        useRedis = false;
        logFallback();
    }

    private void logFallback() {
        log().warn("Redis 不可用，设备影子已降级为内存存储（请确认 Redis 已启动；恢复后会自动切回）");
    }
}
