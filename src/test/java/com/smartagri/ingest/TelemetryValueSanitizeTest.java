package com.smartagri.ingest;

import com.smartagri.alert.AlertEngine;
import com.smartagri.command.CommandService;
import com.smartagri.device.DeviceShadowService;
import com.smartagri.mq.TelemetryMessageBus;
import com.smartagri.mqtt.session.DeviceSessionRegistry;
import com.smartagri.device.Telemetry;
import com.smartagri.websocket.WebSocketPushService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 遥测数值清洗：NaN / Infinity / 1e999 都能通过 JSON 解析，但 InfluxDB 会拒绝这类字段值。
 *
 * <p>一条脏数据会让整次写入抛异常 → 触发全局降级，等于「一台坏传感器打死整个存储」。
 * 低价 DHT22、电容式土壤湿度计产生 NaN 是常态，必须在接入层拦住。</p>
 */
class TelemetryValueSanitizeTest {

    private IngestGateway gateway() {
        return new IngestGateway(
                mock(DeviceShadowService.class),
                mock(TelemetryMessageBus.class),
                mock(WebSocketPushService.class),
                mock(DeviceSessionRegistry.class),
                mock(CommandService.class));
    }

    private byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 数字字面量溢出成 Infinity 的情况——Jackson 拦不住，必须我们自己拦。
     * {@code 1e999} 会解析成 {@code Double.POSITIVE_INFINITY}，是能穿透 JSON 层的真实入口。
     */
    @Test
    void dropsOverflowToInfinityButKeepsValidFields() {
        IngestGateway g = gateway();
        Telemetry t = g.parse("dev-1", json("""
                {"co2": 1e999, "temperature": 26.4, "soilMoisture": 32.5}
                """));

        assertNotNull(t);
        assertEquals(2, t.getMetrics().size(), "溢出成 Infinity 的字段必须被丢弃，其余照常保留");
        assertTrue(t.getMetrics().containsKey("temperature"));
        assertEquals(1, g.invalidValues(), "拦截数量要能被观测到");
    }

    /**
     * 非标准 JSON 的 {@code NaN} / {@code Infinity} 字面量：Jackson 默认拒绝，整帧解析失败。
     * 这一层由 JSON 解析器兜住，属于"帧级丢弃"；上面的 isFinite 兜的是"字段级丢弃"。
     */
    @Test
    void rejectsNonStandardNumericLiteralsAtParserLevel() {
        IngestGateway g = gateway();
        assertNull(g.parse("dev-1", json("{\"temperature\": NaN}")),
                "Jackson 默认不允许 NaN 字面量，整帧应被丢弃");
        assertNull(g.parse("dev-1", json("{\"temperature\": Infinity}")));
    }

    @Test
    void keepsNormalValues() {
        IngestGateway g = gateway();
        Telemetry t = g.parse("dev-1", json("{\"temperature\": 26.4, \"ph\": 6.8}"));

        assertNotNull(t);
        assertEquals(2, t.getMetrics().size());
        assertEquals(0, g.invalidValues());
    }

    @Test
    void returnsNullWhenNothingValid() {
        IngestGateway g = gateway();
        // 整帧都是脏数据 → 不发布到总线，避免下游空转
        assertNull(g.parse("dev-1", json("{\"temperature\": NaN}")));
    }

    @Test
    void ignoresNonNumericFields() {
        IngestGateway g = gateway();
        Telemetry t = g.parse("dev-1", json("{\"temperature\": \"high\", \"humidity\": 55}"));

        assertNotNull(t);
        assertEquals(1, t.getMetrics().size(), "字符串字段不是数值，应忽略而不是当成 0");
        assertEquals(55.0, t.getMetrics().get("humidity"));
    }

    /**
     * 「剔除字段」和「丢弃整帧」必须分开计数。
     *
     * <p>一个 2 字段帧坏 1 个：数据变<b>稀疏</b>，曲线还在，只是那个指标少一个点。
     * 一个 2 字段帧坏 2 个：数据<b>缺失</b>，那个时间戳上什么都没有。
     * 两者对查询端的影响差一个数量级，合并成一个计数器就会把 P3 报成 P0 或者反过来。</p>
     */
    @Test
    void separatesFieldLevelStripFromFrameLevelDrop() {
        IngestGateway g = gateway();

        Telemetry partial = g.parse("dev-1", json("{\"co2\": 1e999, \"temperature\": 26.4}"));
        assertNotNull(partial, "坏 1 个字段不该丢整帧");
        assertEquals(1L, g.sanitizeStats().get("invalidValuesStripped"));
        assertEquals(0L, g.sanitizeStats().get("framesAllInvalidDropped"));
        assertEquals("dev-1", g.sanitizeStats().get("lastInvalidDevice"),
                "光有总数只能知道有人在吐脏数据，不知道是谁在吐");

        assertNull(g.parse("dev-2", json("{\"co2\": 1e999, \"pm25\": -1e999}")),
                "全部字段都坏，整帧必须丢弃，不能让下游拿到空指标帧");
        assertEquals(3L, g.sanitizeStats().get("invalidValuesStripped"), "三次字段级剔除");
        assertEquals(1L, g.sanitizeStats().get("framesAllInvalidDropped"), "一帧整帧丢弃");
        assertEquals("dev-2", g.sanitizeStats().get("lastInvalidDevice"));
    }

    /** JSON 解析失败是第三种：不是数值脏，是报文本身不合法 */
    @Test
    void countsUnparsableFramesSeparately() {
        IngestGateway g = gateway();
        assertNull(g.parse("dev-1", json("not-json")));
        assertEquals(1L, g.sanitizeStats().get("framesUnparsableDropped"));
        assertEquals(0L, g.sanitizeStats().get("framesAllInvalidDropped"));
        assertEquals(0L, g.sanitizeStats().get("invalidValuesStripped"));
    }
}
