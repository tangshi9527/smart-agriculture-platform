package com.smartagri.mqtt.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报文长度上限：这是唯一一条「设备侧能主动把服务端打死」的路径。
 *
 * <p>修复前，{@code remainingLength} 直接取自报文（变长整数最大 4 字节 = 268,435,455），
 * 而 {@code if (in.readableBytes() < total) return;} 会让 Netty 累积缓冲区一路涨到 256MB 才放行——
 * 与 topic ACL 无关（ACL 只看 topic，不看大小）。</p>
 */
class MqttFrameLimitTest {

    @Test
    void rejectsOversizedRemainingLength() {
        EmbeddedChannel ch = new EmbeddedChannel(new MqttDecoder());
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(0x30);                 // PUBLISH, QoS0
        // 4 字节变长整数：0xFF 0xFF 0xFF 0x7F = 268,435,455
        buf.writeByte(0xFF);
        buf.writeByte(0xFF);
        buf.writeByte(0xFF);
        buf.writeByte(0x7F);

        Throwable t = assertThrows(Throwable.class, () -> ch.writeInbound(buf));
        assertTrue(hasTooLong(t), "应以帧过长异常拒绝，实际：" + t);
        assertTrue(MqttDecoder.MAX_REMAINING_LENGTH <= 1024 * 1024, "上限必须明显小于内存量级");
    }

    @Test
    void acceptsNormalTelemetryFrame() {
        EmbeddedChannel ch = new EmbeddedChannel(new MqttDecoder());
        ByteBuf buf = Unpooled.buffer();
        String topic = "device/dev-1/telemetry";
        byte[] payload = "{\"temperature\":26.4}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int remaining = 2 + topic.length() + payload.length;

        buf.writeByte(0x30);
        // 单字节变长整数
        buf.writeByte(remaining);
        buf.writeShort(topic.length());
        buf.writeBytes(topic.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        buf.writeBytes(payload);

        ch.writeInbound(buf);
        Object msg = ch.readInbound();
        assertTrue(msg instanceof MqttMessage.Publish, "正常报文应被正常解码");
    }

    private boolean hasTooLong(Throwable t) {
        while (t != null) {
            if (t instanceof TooLongFrameException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
