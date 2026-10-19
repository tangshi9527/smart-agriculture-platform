package com.smartagri.device;

import com.smartagri.common.Constants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 设备影子：以 Hash 存储设备最新状态与读数，读取是一次 HGET。
 * 离线判定由定时任务依据最后一次心跳时间完成，探测周期默认 1s，
 * 因此判定延迟约等于一个扫描周期（不是严格小于 1s）。
 * <p>
 * 容错：若 Redis 不可用（例如本地未启动 docker-compose），自动降级为进程内内存影子，
 * 设备状态读取、离线判定、WebSocket 在线数推送仍可正常工作，服务不依赖外部组件即可启动。
 */
@Service
public class DeviceShadowService {

    private final StringRedisTemplate redis;

    /** 心跳超时阈值（毫秒）：超过即判定离线 */
    private static final long HEARTBEAT_TIMEOUT_MS = 10_000;

    /** Redis 是否可用；失败后进入内存降级，但会由 {@link #probeRedis()} 定期探测恢复 */
    private volatile boolean useRedis = true;

    /** 内存降级：设备影子(device -> 字段映射) */
    private final ConcurrentHashMap<String, Map<String, String>> memShadows = new ConcurrentHashMap<>();
    /** 内存降级：在线设备集合 */
    private final Set<String> memOnline = ConcurrentHashMap.newKeySet();

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
        if (useRedis) {
            try {
                var h = redis.opsForHash();
                h.put(key, "deviceId", deviceId);
                h.put(key, "status", "online");
                h.put(key, "lastHeartbeat", String.valueOf(System.currentTimeMillis()));
                if (t != null) {
                    h.put(key, "ts", String.valueOf(t.getTs()));
                    for (Map.Entry<String, Double> e : t.getMetrics().entrySet()) {
                        h.put(key, e.getKey(), String.valueOf(e.getValue()));
                    }
                }
                redis.opsForSet().add(Constants.ONLINE_SET, deviceId);
                return;
            } catch (Exception e) {
                useRedis = false;
                logFallback();
            }
        }
        // 内存降级
        Map<String, String> m = memShadows.computeIfAbsent(deviceId, k -> new ConcurrentHashMap<>());
        m.put("deviceId", deviceId);
        m.put("status", "online");
        m.put("lastHeartbeat", String.valueOf(System.currentTimeMillis()));
        if (t != null) {
            m.put("ts", String.valueOf(t.getTs()));
            for (Map.Entry<String, Double> e : t.getMetrics().entrySet()) {
                m.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        memOnline.add(deviceId);
    }

    public void markOffline(String deviceId) {
        String key = Constants.SHADOW_PREFIX + deviceId;
        if (useRedis) {
            try {
                redis.opsForHash().put(key, "status", "offline");
                redis.opsForSet().remove(Constants.ONLINE_SET, deviceId);
                return;
            } catch (Exception e) {
                useRedis = false;
                logFallback();
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
                useRedis = false;
                logFallback();
            }
        }
        Map<String, String> m = memShadows.get(deviceId);
        return m == null ? new HashMap<>() : new HashMap<>(m);
    }

    public List<Map<Object, Object>> getAllShadows() {
        List<Map<Object, Object>> result = new ArrayList<>();
        if (useRedis) {
            try {
                Set<String> keys = redis.keys(Constants.SHADOW_PREFIX + "*");
                if (keys != null) {
                    for (String k : keys) {
                        Map<Object, Object> m = redis.opsForHash().entries(k);
                        if (m == null || m.isEmpty()) {
                            continue;
                        }
                        result.add(m);
                    }
                }
                return result;
            } catch (Exception e) {
                useRedis = false;
                logFallback();
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
                Long size = redis.opsForSet().size(Constants.ONLINE_SET);
                return size == null ? 0 : size;
            } catch (Exception e) {
                useRedis = false;
                logFallback();
            }
        }
        return memOnline.size();
    }

    /**
     * 每秒扫描在线集合：超过心跳超时的设备标记为离线，保证状态恢复/判定 < 1s 粒度。
     */
    @Scheduled(fixedDelay = 1000)
    public void scanOffline() {
        if (useRedis) {
            try {
                Set<String> online = redis.opsForSet().members(Constants.ONLINE_SET);
                if (online == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                for (String deviceId : online) {
                    String hb = (String) redis.opsForHash().get(Constants.SHADOW_PREFIX + deviceId, "lastHeartbeat");
                    if (hb == null || now - Long.parseLong(hb) > HEARTBEAT_TIMEOUT_MS) {
                        markOffline(deviceId);
                    }
                }
                return;
            } catch (Exception e) {
                useRedis = false;
                logFallback();
            }
        }
        long now = System.currentTimeMillis();
        for (String deviceId : new ArrayList<>(memOnline)) {
            Map<String, String> m = memShadows.get(deviceId);
            if (m == null) {
                memOnline.remove(deviceId);
                continue;
            }
            String hb = m.get("lastHeartbeat");
            if (hb == null || now - Long.parseLong(hb) > HEARTBEAT_TIMEOUT_MS) {
                markOffline(deviceId);
            }
        }
    }

    public Map<String, Object> summary() {
        Map<String, Object> s = new HashMap<>();
        s.put("online", onlineCount());
        s.put("total", getAllShadows().size());
        s.put("redisAvailable", useRedis);
        return s;
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
                useRedis = false;
                logFallback();
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
                useRedis = false;
                logFallback();
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
            redis.hasKey(Constants.ONLINE_SET);
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
                redis.opsForSet().add(Constants.ONLINE_SET, memOnline.toArray(new String[0]));
                // 回写心跳时间，避免刚切回就被离线扫描判死
                for (String id : memOnline) {
                    h.put(Constants.SHADOW_PREFIX + id, "lastHeartbeat", String.valueOf(System.currentTimeMillis()));
                }
            }
            memShadows.clear();
            memOnline.clear();
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

    private void logFallback() {
        log().warn("Redis 不可用，设备影子已降级为内存存储（请确认 Redis 已启动；恢复后会自动切回）");
    }
}
