package com.smartagri.device;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 离线判定的开销与容错。
 *
 * <p>上一版 {@code scanOffline()} 是 SMEMBERS 拿全量在线设备，再对每一台
 * {@code HGET lastHeartbeat}——1000 台设备就是每秒 1 + 1000 次 Redis 往返。
 * 本机 loopback 撑得住，真实网络 RTT 1ms 时这个扫描根本追不上心跳。
 * 心跳时间做成 ZSET 的 score 之后，判定压成一次 {@code ZRANGEBYSCORE}。</p>
 */
class DeviceShadowScanTest {

    @Test
    @SuppressWarnings("unchecked")
    void scanIsOneRoundTripInsteadOfOnePerDevice() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForHash()).thenReturn(hash);
        Set<String> expired = new LinkedHashSet<>();
        expired.add("dev-old");
        when(zset.rangeByScore(eq("agri:online"), anyDouble(), anyDouble())).thenReturn(expired);

        DeviceShadowService svc = new DeviceShadowService(redis);
        svc.scanOffline();

        // 关键断言：判定只用一次范围查询
        verify(zset, times(1)).rangeByScore(eq("agri:online"), anyDouble(), anyDouble());
        // 而且绝不能再逐台把 lastHeartbeat HGET 回来比对
        verify(hash, never()).get(anyString(), eq("lastHeartbeat"));
        // 超时的设备仍要被摘出在线集合
        verify(zset, times(1)).remove("agri:online", "dev-old");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyExpiredSetMeansNoWriteAtAll() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rangeByScore(eq("agri:online"), anyDouble(), anyDouble()))
                .thenReturn(new LinkedHashSet<>());

        DeviceShadowService svc = new DeviceShadowService(redis);
        svc.scanOffline();

        // 正常运行时没有任何设备超时，这一轮扫描应当零写入、零额外往返
        verify(zset, never()).remove(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void garbageHeartbeatOnOneDeviceDoesNotDragOthersDown() {
        // Redis 挂 → 走内存影子路径
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForHash()).thenThrow(new RuntimeException("connection refused"));
        DeviceShadowService svc = new DeviceShadowService(redis);

        svc.markOnline("dev-ok", null);
        svc.markOnline("dev-bad", null);
        // setControl 直接写内存影子的字段，正好用来把一台设备的心跳写成垃圾
        svc.setControl("dev-bad", "lastHeartbeat", "not-a-number");

        // 上一版这里会抛 NumberFormatException，被 catch(Exception) 当成「Redis 挂了」，
        // 于是一台设备的脏字段把全部设备的状态切进内存影子
        assertDoesNotThrow(svc::scanOffline);
        assertEquals(2, svc.onlineCount(),
                "心跳字段坏掉的设备只跳过它自己，正常设备不能被连坐判离线");
        assertEquals(1L, svc.summary().get("badHeartbeats"), "脏心跳必须计数，否则坏了没人知道");
    }

    @Test
    @SuppressWarnings("unchecked")
    void wrongTypeIsResetInsteadOfTopplingRedis() {
        // 在线集合从 SET 改成 ZSET 之后，旧部署的 Redis 里那个 key 还是 SET 类型，
        // ZADD 会报 WRONGTYPE。如果这个异常被当成「Redis 挂了」，会陷入
        // 「降级 → 探活 ping 通 → 回写又 WRONGTYPE → 继续降级」的死循环。
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.add(anyString(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("WRONGTYPE Operation against a key holding the wrong kind of value"));
        when(redis.delete(anyString())).thenReturn(Boolean.TRUE);

        DeviceShadowService svc = new DeviceShadowService(redis);
        svc.markOnline("dev-1", null);

        verify(redis).delete("agri:online");
        assertTrue(svc.isRedisAvailable(),
                "类型不匹配是升级时的一次性问题，绝不能因此把整个设备影子打进内存降级");
    }
}
