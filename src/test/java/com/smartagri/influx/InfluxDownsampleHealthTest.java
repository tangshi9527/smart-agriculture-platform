package com.smartagri.influx;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 降采样任务停摆的判定与抢救。
 *
 * <p>为什么这条比其它缺陷值得先修：给 bucket 加 retention（raw 30 天）本身是为了防磁盘炸，
 * 但它同时引入一条<b>新的静默丢失路径</b>——task 停摆后新数据照常写进 raw、却没有东西写进 1m 库，
 * 30 天后 raw 的 retention 把没被降采样的数据淘汰，永久丢失，且服务、曲线、health 全部正常。
 * 「修复引入了新失效模式」比「原有缺陷没修」更值得优先处理。</p>
 */
class InfluxDownsampleHealthTest {

    private InfluxService service() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        QueryApi queryApi = mock(QueryApi.class);
        when(client.getQueryApi()).thenReturn(queryApi);
        when(queryApi.query(anyString(), anyString())).thenReturn(List.<FluxTable>of());
        return new InfluxService(client);
    }

    /** 没探过之前必须是 unknown，不能报 healthy=true —— 那是虚假健康 */
    @Test
    void reportsUnknownBeforeFirstCheck() {
        InfluxService svc = service();

        Map<String, Object> h = svc.downsampleHealth();
        assertEquals("unknown", h.get("status"));
        assertNull(h.get("healthy"), "一次都没检查过却输出 healthy=true，监控会以为它正常");
        assertNull(h.get("lastCheckAt"));
        assertNull(h.get("lastTaskSuccessAt"));
    }

    /** 正常态：ds 跟得上 raw，healthy 且刷新 lastTaskSuccessAt */
    @Test
    void marksHealthyWhenDownsampleKeepsUp() {
        InfluxService svc = service();
        long now = System.currentTimeMillis();

        svc.checkDownsampleFreshness(now - 5_000, now - 30_000, now);

        Map<String, Object> h = svc.downsampleHealth();
        assertEquals("ok", h.get("status"));
        assertEquals(Boolean.TRUE, h.get("healthy"));
        assertEquals(now, h.get("lastTaskSuccessAt"));
    }

    /** 停摆：raw 在写入、ds 落后超过阈值 → 判停摆，并把 raw retention 拉长（抢救） */
    @Test
    void marksStaleWhenDownsampleLagsBeyondThreshold() {
        InfluxService svc = service();
        long now = System.currentTimeMillis();
        long threshold = (Long) svc.downsampleHealth().get("lagThresholdMs");

        // raw 有 5 秒前的点，ds 最新点却在 1 小时前
        svc.checkDownsampleFreshness(now - 5_000, now - 3_600_000, now);

        Map<String, Object> h = svc.downsampleHealth();
        assertEquals(Boolean.FALSE, h.get("healthy"), "ds 落后 1 小时必须判停摆");
        assertEquals(1L, h.get("staleProbes"));
        assertTrue(threshold > 0);

        // 再探一次仍然是停摆，staleProbes 累加（用于抑制重复日志，不是重复报错）
        svc.checkDownsampleFreshness(now - 5_000, now - 3_600_000, now + 300_000);
        assertEquals(2L, svc.downsampleHealth().get("staleProbes"));
    }

    /** 采集本身停了（设备全掉线）：ds 落后是正常的，不能误判成 task 挂了 */
    @Test
    void doesNotBlameTaskWhenIngestionItselfStopped() {
        InfluxService svc = service();
        long now = System.currentTimeMillis();

        // raw 最新点已经是 3 小时前 → 没有新数据，无从判断 task
        svc.checkDownsampleFreshness(now - 10_800_000, now - 10_860_000, now);

        assertEquals(Boolean.TRUE, svc.downsampleHealth().get("healthy"),
                "没有新数据时不能报 task 停摆，否则掉线就会触发误报");
        assertEquals(0L, svc.downsampleHealth().get("staleProbes"));
        assertEquals("ingestion-stopped", svc.downsampleHealth().get("status"),
                "结论要能区分「采集停了」和「task 挂了」");
    }

    /** 查不到 raw 数据时同样不下结论 */
    @Test
    void staysSilentWhenRawHasNoData() {
        InfluxService svc = service();
        long now = System.currentTimeMillis();

        svc.checkDownsampleFreshness(0, 0, now);

        assertEquals(Boolean.TRUE, svc.downsampleHealth().get("healthy"));
    }

    /**
     * 恢复：补算完成后留下 gap 记录，且标记 backfilled=true。
     * 这条 gap 是给查询端看的——降采样库在 [start, end] 曾经是空的。
     */
    @Test
    void recordsBackfilledGapOnRecovery() {
        InfluxService svc = service();
        long now = System.currentTimeMillis();

        svc.checkDownsampleFreshness(now - 5_000, now - 3_600_000, now);
        assertEquals(Boolean.FALSE, svc.downsampleHealth().get("healthy"));

        // 10 分钟后 ds 重新跟上
        svc.checkDownsampleFreshness(now + 600_000, now + 590_000, now + 600_000);

        Map<String, Object> h = svc.downsampleHealth();
        assertEquals(Boolean.TRUE, h.get("healthy"));
        assertEquals(0L, h.get("staleProbes"), "恢复后连续计数要清零");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gaps = (List<Map<String, Object>>) h.get("gaps");
        assertEquals(1, gaps.size(), "停摆窗口必须留痕，否则 ds 库的空洞无声无息");
        assertEquals(Boolean.TRUE, gaps.get(0).get("backfilled"));
    }
}
