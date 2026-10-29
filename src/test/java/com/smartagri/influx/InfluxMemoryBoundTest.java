package com.smartagri.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.smartagri.device.Telemetry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * 降级内存缓冲的<b>全局</b>上限。
 *
 * <p>原来只有每设备上限 {@code MAX_MEM_POINTS_PER_DEVICE=3000}，总量等于「每台上限 × 设备数」，
 * 而设备数是外部输入（设备增多、或伪造 deviceId 灌入），所以总量无界：
 * 1000 台约 200~300MB，3000 台接近 1GB。堆配的是 {@code -Xmx4g}，靠堆大撑住了，
 * 但那是运气不是设计。</p>
 *
 * <p>这套测试要守住三件事：</p>
 * <ol>
 *   <li>总量真的不会越过上限；</li>
 *   <li>超限时是<b>整台</b>淘汰最久没新数据的设备，不是把所有设备的曲线都删得七零八落；</li>
 *   <li>计数账本和容器实际内容永远一致——账本偏大就会「明明没数据却一直判超限」，
 *       偏小则上限失效。这个一致性比上限本身更容易出错。</li>
 * </ol>
 */
class InfluxMemoryBoundTest {

    /** 造一台设备的 n 个点（每点一个指标，便于精确控制点数） */
    private void stash(InfluxService svc, String device, int points) {
        for (int i = 0; i < points; i++) {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("t" + i, 20.0 + i);
            Telemetry t = new Telemetry();
            t.setDeviceId(device);
            t.setTs(System.currentTimeMillis() + i);
            t.setMetrics(m);
            svc.write(t);
        }
    }

    /** 降级态（influxAvailable=false）下写入才会走 stashToMemory */
    private InfluxService degraded() {
        InfluxService svc = new InfluxService(mock(InfluxDBClient.class));
        svc.setWriteApiForTest(mock(WriteApiBlocking.class));
        svc.setInfluxAvailableForTest(false);
        return svc;
    }

    private long evicted(InfluxService svc) {
        return ((Number) svc.writeStats().get("evictedFromMemory")).longValue();
    }

    @Test
    void 总量上限生效且不会越界() {
        InfluxService svc = degraded();
        svc.setMaxMemoryTotalPointsForTest(100);

        // 10 台 × 每台 20 点 = 200 点，上限 100，必须只留一半
        for (int d = 0; d < 10; d++) {
            stash(svc, "dev-" + d, 20);
        }

        assertEquals(100, svc.memoryPointCount(), "总量必须被压在配置的上限内");
        assertEquals(100, evicted(svc), "写入 200 点、留 100 点，淘汰数应当正好 100");
    }

    @Test
    void 超限时整台淘汰最久没有新数据的设备() {
        InfluxService svc = degraded();
        svc.setMaxMemoryTotalPointsForTest(30);

        stash(svc, "dev-0", 10);   // 最早写的
        stash(svc, "dev-1", 10);
        stash(svc, "dev-2", 10);   // 到此正好 30，等于上限，不该触发淘汰
        assertEquals(30, svc.memoryPointCount(), "等于上限不算超限，不该淘汰");
        assertEquals(3, svc.memoryDeviceCountForTest());

        stash(svc, "dev-3", 10);   // 40 > 30，必须淘汰 dev-0 整台

        assertEquals(30, svc.memoryPointCount());
        assertEquals(10, evicted(svc));
        assertEquals(3, svc.memoryDeviceCountForTest(),
                "设备数必须是 3 而不是 4——减点数和减设备数同时发生，才说明是整台淘汰而不是逐点删");
    }

    @Test
    void 每设备上限依然独立生效() {
        InfluxService svc = degraded();
        svc.setMaxMemoryTotalPointsForTest(0);   // 0 = 不限全局，只测每设备

        stash(svc, "dev-0", 3500);

        assertEquals(3000, svc.memoryPointCount(), "单台仍受 3000 点的上限约束");
        assertEquals(500, evicted(svc), "超出每台上限的 500 点应计入淘汰");
    }

    @Test
    void 上限为零表示不限制() {
        InfluxService svc = degraded();
        svc.setMaxMemoryTotalPointsForTest(0);

        stash(svc, "dev-0", 100);
        stash(svc, "dev-1", 100);

        assertEquals(200, svc.memoryPointCount(), "配置为 0 时全局不设限（只用于调试，生产不该这么配）");
        assertEquals(0, evicted(svc));
    }

    @Test
    void 计数账本必须与容器实际一致() {
        InfluxService svc = degraded();
        svc.setMaxMemoryTotalPointsForTest(250);

        // 混合规模：既有超过每台上限的，也有正好卡在全局上限附近的
        for (int d = 0; d < 8; d++) {
            stash(svc, "dev-" + d, 40 + d * 5);
        }

        assertEquals(svc.memoryPointCountByScan(), svc.memoryPointCount(),
                "O(1) 计数必须等于遍历求和。不等就说明有地方改了容器却没记账——"
                        + "账本偏大会让系统永久误判超限，偏小则上限形同不存在");
    }

    @Test
    void 补写清空后计数归零而不是残留() {
        InfluxService svc = degraded();
        WriteApiBlocking api = mock(WriteApiBlocking.class);
        doNothing().when(api).writePoints(any(), any(), anyList());

        stash(svc, "dev-0", 3);
        stash(svc, "dev-1", 3);
        assertEquals(6, svc.memoryPointCount());

        int flushed = svc.flushMemoryBacklog(api);

        assertEquals(6, flushed, "6 个点全部补写成功");
        assertEquals(0, svc.memoryPointCount(), "补写成功后计数必须归零");
        assertEquals(0, svc.memoryPointCountByScan(), "容器也必须真的空了");
        assertEquals(0, svc.memoryDeviceCountForTest());
    }

    @Test
    void 补写只减已写回的那部分点数() {
        InfluxService svc = degraded();
        // 第一次写成功，第二次抛异常：模拟补写途中链路又断
        WriteApiBlocking api = mock(WriteApiBlocking.class);
        doNothing().doThrow(new RuntimeException("link down"))
                .when(api).writePoints(any(), any(), anyList());

        // 每台 2000 点、共 3 台 = 6000 点，超过 WRITE_BATCH_SIZE(5000)，
        // 所以第一批 5000 点发成功，第二批 1000 点抛异常
        stash(svc, "dev-0", 2000);
        stash(svc, "dev-1", 2000);
        stash(svc, "dev-2", 2000);
        assertEquals(6000, svc.memoryPointCount());

        try {
            svc.flushMemoryBacklog(api);
        } catch (Exception expected) {
            // 补写失败要把异常抛给调用方（由它保持降级态、下一轮重试）
        }

        int remaining = svc.memoryPointCount();
        assertEquals(1000, remaining,
                "第一批 5000 点写成功后必须出账，剩下的 1000 点必须还在内存里——"
                        + "上一版是先清容器再发最后一批，这里会变成 0，那 1000 点就永久丢了");
        assertEquals(svc.memoryPointCountByScan(), remaining,
                "失败路径同样不能把账本改坏——否则下一轮会误判为已清空而丢数据");
    }
}
