package com.smartagri.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import com.influxdb.exceptions.InfluxException;
import com.smartagri.device.Telemetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写入失败的分类处理：<b>「网络不通」和「这条数据服务端不要」必须分开。</b>
 *
 * <p>上一版是 {@code catch (Exception e) → influxAvailable = false}，两种情况一个待遇：</p>
 * <ul>
 *   <li>网络抖一次，几百台设备的查询全部切进内存，哪怕第二秒就恢复了；</li>
 *   <li>字段类型冲突这类 HTTP 400 会让系统<b>永久</b>留在降级态——重试永远是 400，
 *       于是正常数据也开始往内存堆，最终再次 OOM。</li>
 * </ul>
 */
class InfluxWriteFailureTest {

    private Telemetry tel(String device, Map<String, Double> metrics) {
        Telemetry t = new Telemetry();
        t.setDeviceId(device);
        t.setTs(System.currentTimeMillis());
        t.setMetrics(metrics);
        return t;
    }

    private InfluxService serviceWith(WriteApiBlocking api) {
        InfluxService svc = new InfluxService(mock(InfluxDBClient.class));
        svc.setWriteApiForTest(api);
        svc.setInfluxAvailableForTest(true);
        return svc;
    }

    private InfluxException influxError(int status, String message) {
        InfluxException e = mock(InfluxException.class);
        when(e.status()).thenReturn(status);
        when(e.getMessage()).thenReturn(message);
        return e;
    }

    private static long stat(InfluxService svc, String key) {
        return ((Number) svc.writeStats().get(key)).longValue();
    }

    private void throwOnWrite(WriteApiBlocking api, InfluxException e) {
        doThrow(e).when(api).writePoints(any(), any(), ArgumentMatchers.<Point>anyList());
    }

    @Test
    void clientErrorIsDroppedWithoutDegrading() {
        WriteApiBlocking api = mock(WriteApiBlocking.class);
        throwOnWrite(api, influxError(400, "partial write: field type conflict"));
        InfluxService svc = serviceWith(api);

        boolean handled = svc.writeBatchForTest(List.of(tel("dev-1", Map.of("temperature", 25.0))));

        assertTrue(handled, "被服务端明确拒绝的批算「已处理」，不该无限重试");
        assertTrue(svc.isInfluxAvailable(),
                "HTTP 400 是数据本身的问题，重试一万次结果一样。因此它绝不能触发全平台降级");
        assertEquals(1, stat(svc, "rejectedByServer"));
        assertEquals(0, svc.memoryPointCount(),
                "被拒绝的点不该再进内存——它永远不会变合法，只会占内存");
    }

    @Test
    void transportFailureKeepsServiceUpUntilThreshold() {
        WriteApiBlocking api = mock(WriteApiBlocking.class);
        throwOnWrite(api, influxError(503, "connection reset"));
        InfluxService svc = serviceWith(api);
        List<Telemetry> batch = List.of(tel("dev-1", Map.of("temperature", 25.0)));

        svc.writeBatchForTest(batch);
        svc.writeBatchForTest(batch);
        assertTrue(svc.isInfluxAvailable(),
                "连续失败没到阈值就降级，等于「一次网络抖动打死全平台」——比不降级更糟");
        assertEquals(2, svc.memoryPointCount(), "失败的批必须转存内存，等恢复后补写，不能直接扔");

        svc.writeBatchForTest(batch);
        assertFalse(svc.isInfluxAvailable(), "连续 3 批都失败，才认定服务真的不通");
    }

    @Test
    void successResetsTheFailureCounter() {
        WriteApiBlocking api = mock(WriteApiBlocking.class);
        InfluxException e = influxError(503, "timeout");
        // 失败 失败 成功 失败 失败 → 中间那次成功必须把计数清零，
        // 否则「连续失败」就变成了「累计失败」，一条偶发抖动会让计数永远逼近阈值
        doThrow(e).doThrow(e).doNothing().doThrow(e).doThrow(e)
                .when(api).writePoints(any(), any(), ArgumentMatchers.<Point>anyList());
        InfluxService svc = serviceWith(api);
        List<Telemetry> batch = List.of(tel("dev-1", Map.of("temperature", 25.0)));

        for (int i = 0; i < 5; i++) {
            svc.writeBatchForTest(batch);
        }

        // 序列：失败 失败 成功 失败 失败 → 中间那次成功必须把计数清零，否则「连续」就变成了「累计」
        assertTrue(svc.isInfluxAvailable(), "成功过一次就说明链路是通的，失败计数必须归零");
    }

    @Test
    void nonFiniteFieldsAreSkippedInsteadOfPoisoningTheWholeBatch() {
        WriteApiBlocking api = mock(WriteApiBlocking.class);
        InfluxService svc = serviceWith(api);

        Map<String, Double> metrics = new HashMap<>();
        metrics.put("temperature", 25.0);
        metrics.put("humidity", Double.NaN);
        metrics.put("light", Double.POSITIVE_INFINITY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Point>> captor = ArgumentCaptor.forClass(List.class);
        svc.writeBatchForTest(List.of(tel("dev-1", metrics)));

        verify(api).writePoints(any(), any(), captor.capture());
        List<Point> sent = captor.getValue();
        assertEquals(1, sent.size());
        String line = sent.get(0).toLineProtocol();
        // 攒批把「一条脏数据」的影响放大了 N 倍：NaN 写进行协议是非法的，
        // 服务端会 400 退回整批。所以必须在组批阶段逐字段剔除。
        assertFalse(line.contains("humidity"), "NaN 字段必须剔除，否则整批 500 条都会被拒: " + line);
        assertFalse(line.contains("light"), "Infinity 同样非法: " + line);
        assertTrue(line.contains("temperature"), "合法字段要照常写入");
        assertEquals(2, stat(svc, "invalidFieldsSkipped"));
    }

    @Test
    void telemetryWithNoValidFieldIsNotSentAtAll() {
        WriteApiBlocking api = mock(WriteApiBlocking.class);
        InfluxService svc = serviceWith(api);

        Map<String, Double> metrics = new HashMap<>();
        metrics.put("temperature", Double.NaN);

        svc.writeBatchForTest(List.of(tel("dev-1", metrics)));

        verify(api, never()).writePoints(any(), any(), ArgumentMatchers.<Point>anyList());
    }
}
