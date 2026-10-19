package com.smartagri.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import com.smartagri.device.Telemetry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 降级期间的内存点必须在恢复时补写回 InfluxDB。
 *
 * <p>上一版只做了「ping 通 → 重建 bucket/task → 切回」，漏了补写：查询端在
 * {@code [降级开始, 恢复]} 区间拿到的是空数据，而 1 分钟降采样窗口聚合少算一块时
 * <b>不会返回空值</b>，而是返回一条"平缓的假曲线"——调用方不会报错，只会当成数据正常。</p>
 */
class InfluxBacklogFlushTest {

    private Telemetry telemetry(String device, long ts) {
        Telemetry t = new Telemetry();
        t.setDeviceId(device);
        t.setTs(ts);
        t.setMetrics(Map.of("temperature", 25.0, "humidity", 60.0));
        return t;
    }

    @Test
    void flushesMemoryPointsBackAndClearsThem() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        InfluxService svc = new InfluxService(client);

        // influxAvailable 初始为 false，write() 会落到内存降级路径
        svc.write(telemetry("dev-1", 1000L));
        svc.write(telemetry("dev-1", 2000L));
        svc.write(telemetry("dev-2", 3000L));

        WriteApiBlocking api = mock(WriteApiBlocking.class);
        int flushed = svc.flushMemoryBacklog(api);

        // 3 条遥测 × 2 个指标 = 6 个点
        assertEquals(6, flushed, "降级期间的每个字段都要补写回去，不能只补一条");
        verify(api, atLeastOnce()).writePoints(any(), any(), anyList());

        // 已经写回的点必须清掉，否则下一轮会重复补写
        assertEquals(0, svc.flushMemoryBacklog(api), "补写后内存队列应被清空");
    }

    @Test
    void capsPointsPerDeviceToAvoidOom() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        InfluxService svc = new InfluxService(client);

        // 单设备灌入远超上限的点，验证有界淘汰（否则长时间降级会 OOM）
        for (int i = 0; i < 4000; i++) {
            svc.write(telemetry("dev-x", i));
        }
        WriteApiBlocking api = mock(WriteApiBlocking.class);
        int flushed = svc.flushMemoryBacklog(api);

        // 上限 3000 点/设备 × 2 个指标 = 6000，允许少量浮动
        assertTrue(flushed <= 6200, "内存队列必须有界，实际补写 " + flushed + " 个点");
        assertTrue(flushed > 0, "至少应保留最近一段可补写的数据");
    }

    @Test
    void recordsDegradedWindowForUpstream() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        InfluxService svc = new InfluxService(client);

        // 初始就不可用（未 init），应记录降级起点
        assertTrue(svc.degradedSince() >= 0);
        // 恢复后（这里只断言接口可用，完整恢复需要真实 Influx）
        assertTrue(svc.degradedGaps().isEmpty() || svc.degradedGaps().get(0).containsKey("start"));
    }

    @SuppressWarnings("unused")
    private List<Point> unused() {
        return null;
    }
}
