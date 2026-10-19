package com.smartagri.device;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 降级恢复：修复前 {@code useRedis=false} 是单向门——Redis 抖一次就永久走内存影子，
 * 多实例各算各的、重启全丢。现在 30s 探一次，恢复时先把内存状态回写再切回。
 */
class DeviceShadowRecoveryTest {

    @SuppressWarnings("unchecked")
    private StringRedisTemplate newRedis() {
        return mock(StringRedisTemplate.class);
    }

    private Telemetry telemetry() {
        Telemetry t = new Telemetry();
        t.setDeviceId("dev-1");
        t.setTs(1000L);
        t.setMetrics(Map.of("temperature", 25.5));
        return t;
    }

    @Test
    @SuppressWarnings("unchecked")
    void recoversFromMemoryFallbackAndFlushesState() {
        StringRedisTemplate redis = newRedis();
        // 一开始 Redis 挂了
        when(redis.opsForHash()).thenThrow(new RuntimeException("connection refused"));

        DeviceShadowService svc = new DeviceShadowService(redis);
        svc.markOnline("dev-1", telemetry());
        assertFalse(svc.isRedisAvailable(), "Redis 异常后应降级为内存");

        // Redis 恢复
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        SetOperations<String, String> set = mock(SetOperations.class);
        // 用 doReturn 而不是 when(...)：此时 opsForHash() 仍处于抛异常状态，
        // when() 会把异常带出来，doReturn 不会真正调用方法
        doReturn(hash).when(redis).opsForHash();
        doReturn(set).when(redis).opsForSet();
        when(redis.hasKey(anyString())).thenReturn(true);

        svc.probeRedis();

        assertTrue(svc.isRedisAvailable(), "探测成功后必须切回 Redis，否则就是单向门");
        // 降级期间的内存状态必须回写，否则切回瞬间数据断层
        verify(hash).putAll(eq("agri:shadow:dev-1"), anyMap());
        verify(set).add(eq("agri:online"), any(String[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void staysDegradedWhenRedisStillDown() {
        StringRedisTemplate redis = newRedis();
        when(redis.opsForHash()).thenThrow(new RuntimeException("down"));
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("still down"));

        DeviceShadowService svc = new DeviceShadowService(redis);
        svc.markOnline("dev-1", telemetry());
        assertFalse(svc.isRedisAvailable());

        svc.probeRedis(); // 探测仍失败，不应抛异常
        assertFalse(svc.isRedisAvailable(), "Redis 仍不可用时保持降级，且不能把内存数据清掉");
        // 内存影子仍然可读，服务不中断
        assertFalse(svc.getShadow("dev-1").isEmpty());
    }
}
