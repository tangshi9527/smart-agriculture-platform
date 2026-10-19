package com.smartagri.mqtt.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQTT 字段级边界：帧长上限只能关住「单帧总量」，关不住「声明大、实际小」。
 *
 * <p>MQTT 用 uint16 表示每个字符串字段的长度，解码器按<b>声明值</b>分配数组。
 * 一帧只有 20 字节、但每个字段都声明 65535，就能让解码器按声明值分配、并按声明值推进位置。
 * 上一版没有任何校验，越界后抛的是 {@code IndexOutOfBoundsException}——连接确实会被关掉，
 * 但错误信息看不出是哪个字段、声明了多少，排查时等于没有信息。</p>
 */
class MqttFieldBoundTest {

    private static void writeVarint(ByteBuf buf, int value) {
        do {
            int b = value % 128;
            value /= 128;
            if (value > 0) {
                b |= 0x80;
            }
            buf.writeByte(b);
        } while (value > 0);
    }

    /** CONNECT：clientId 声明 65535 字节，实际只给 5 字节 */
    @Test
    void rejectsConnectWithOverDeclaredClientId() {
        EmbeddedChannel ch = new EmbeddedChannel(new MqttDecoder());
        ByteBuf body = Unpooled.buffer();
        body.writeShort(4);
        body.writeBytes("MQTT".getBytes(StandardCharsets.UTF_8));
        body.writeByte(4);        // protocol level
        body.writeByte(0x02);     // cleanSession
        body.writeShort(60);      // keepAlive
        body.writeShort(65535);   // clientId 声明长度
        body.writeBytes("dev-1".getBytes(StandardCharsets.UTF_8));

        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(0x10);    // CONNECT
        writeVarint(frame, body.readableBytes());
        frame.writeBytes(body);

        Throwable t = assertThrows(Throwable.class, () -> ch.writeInbound(frame));
        assertTrue(rootMessage(t).contains("clientId"), "错误信息必须指出是哪个字段，实际：" + rootMessage(t));
        assertTrue(rootMessage(t).contains("65535"), "错误信息必须带上声明值，实际：" + rootMessage(t));
    }

    /** CONNECT：clientId 声明 100 字节（未超字段上限），但帧里只剩 5 字节 */
    @Test
    void rejectsConnectWithTruncatedClientId() {
        EmbeddedChannel ch = new EmbeddedChannel(new MqttDecoder());
        ByteBuf body = Unpooled.buffer();
        body.writeShort(4);
        body.writeBytes("MQTT".getBytes(StandardCharsets.UTF_8));
        body.writeByte(4);
        body.writeByte(0x02);
        body.writeShort(60);
        body.writeShort(100);     // 声明 100，实际 5
        body.writeBytes("dev-1".getBytes(StandardCharsets.UTF_8));

        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(0x10);
        writeVarint(frame, body.readableBytes());
        frame.writeBytes(body);

        Throwable t = assertThrows(Throwable.class, () -> ch.writeInbound(frame));
        assertTrue(rootMessage(t).contains("超出报文实际长度"), "实际：" + rootMessage(t));
    }

    /** SUBSCRIBE：topic filter 个数上限 */
    @Test
    void rejectsSubscribeWithTooManyTopicFilters() {
        EmbeddedChannel ch = new EmbeddedChannel(new MqttDecoder());
        ByteBuf body = Unpooled.buffer();
        body.writeShort(1);       // packetId
        int n = MqttDecoder.MAX_TOPIC_FILTERS + 1;
        for (int i = 0; i < n; i++) {
            body.writeShort(1);
            body.writeByte('a');
            body.writeByte(0);    // QoS0
        }

        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(0x82);    // SUBSCRIBE
        writeVarint(frame, body.readableBytes());
        frame.writeBytes(body);

        Throwable t = assertThrows(Throwable.class, () -> ch.writeInbound(frame));
        assertTrue(rootMessage(t).contains("topic filter"), "实际：" + rootMessage(t));
    }

    /** SUBSCRIBE：单个 filter 声明长度超出帧尾 */
    @Test
    void rejectsSubscribeWithTruncatedFilter() {
        EmbeddedChannel ch = new EmbeddedChannel(new MqttDecoder());
        ByteBuf body = Unpooled.buffer();
        body.writeShort(1);
        body.writeShort(500);     // 声明 500 字节
        body.writeBytes("abc".getBytes(StandardCharsets.UTF_8));

        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(0x82);
        writeVarint(frame, body.readableBytes());
        frame.writeBytes(body);

        Throwable t = assertThrows(Throwable.class, () -> ch.writeInbound(frame));
        assertTrue(rootMessage(t).contains("topicFilter"), "实际：" + rootMessage(t));
    }

    /**
     * 中文 topic 必须按字节推进位置。
     *
     * <p>上一版用 {@code String.length()}（字符数）推进 pos，一个汉字占 3 字节，
     * {@code 设备/一号棚/telemetry} 是 13 个字符、29 个字节——pos 会少走 16 字节，
     * 载荷从报文中间开始切，topic 解析出乱码却不会报错。</p>
     */
    @Test
    void parsesMultibyteTopicByByteLength() {
        EmbeddedChannel ch = new EmbeddedChannel(new MqttDecoder());
        String topic = "设备/一号棚/telemetry";
        byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
        byte[] payload = "{\"temperature\":26.4}".getBytes(StandardCharsets.UTF_8);

        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(0x30);
        writeVarint(frame, 2 + topicBytes.length + payload.length);
        frame.writeShort(topicBytes.length);   // 字节长度，不是字符数
        frame.writeBytes(topicBytes);
        frame.writeBytes(payload);

        ch.writeInbound(frame);
        Object msg = ch.readInbound();
        assertTrue(msg instanceof MqttMessage.Publish, "中文 topic 的正常帧必须能解码");
        MqttMessage.Publish p = (MqttMessage.Publish) msg;
        assertEquals(topic, p.getTopic());
        assertEquals("{\"temperature\":26.4}", new String(p.getPayload(), StandardCharsets.UTF_8));
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        String msg = String.valueOf(t);
        while (cur != null) {
            if (cur.getMessage() != null) {
                msg = cur.getMessage();
            }
            cur = cur.getCause();
        }
        return msg;
    }
}
