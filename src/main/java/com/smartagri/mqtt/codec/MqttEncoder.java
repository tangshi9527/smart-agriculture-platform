package com.smartagri.mqtt.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;

/**
 * MQTT 3.1.1 二进制报文编码器（双向：既编码服务端下行报文，也编码客户端上行报文）。
 */
public class MqttEncoder extends MessageToByteEncoder<MqttMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, MqttMessage msg, ByteBuf out) {
        if (msg instanceof MqttMessage.ConnAck ack) {
            out.writeByte(2 << 4);
            writeRemaining(out, 2);
            out.writeByte(0);
            out.writeByte(ack.getReturnCode());
        } else if (msg instanceof MqttMessage.SubAck sub) {
            int remaining = 2 + sub.getReturnCodes().size();
            out.writeByte(9 << 4);
            writeRemaining(out, remaining);
            out.writeShort(sub.getPacketId());
            for (Integer code : sub.getReturnCodes()) {
                out.writeByte(code);
            }
        } else if (msg instanceof MqttMessage.PubAck pub) {
            out.writeByte(4 << 4);
            writeRemaining(out, 2);
            out.writeShort(pub.getPacketId());
        } else if (msg instanceof MqttMessage.PingResp) {
            out.writeByte(13 << 4);
            writeRemaining(out, 0);
        } else if (msg instanceof MqttMessage.Connect c) {
            byte[] clientId = c.getClientId().getBytes(StandardCharsets.UTF_8);
            boolean hasUser = c.getUsername() != null;
            boolean hasPass = c.getPassword() != null;
            int connectFlags = 0;
            if (c.isCleanSession()) {
                connectFlags |= 0x02;
            }
            if (hasUser) {
                connectFlags |= 0x80; // User Name Flag
            }
            if (hasPass) {
                connectFlags |= 0x40; // Password Flag
            }
            int payloadLen = 2 + clientId.length; // clientId
            if (hasUser) {
                payloadLen += 2 + c.getUsername().getBytes(StandardCharsets.UTF_8).length;
            }
            if (hasPass) {
                payloadLen += 2 + c.getPassword().getBytes(StandardCharsets.UTF_8).length;
            }
            int remaining = 10 + payloadLen; // 可变头(协议名6+级别1+标志1+保活2) + 载荷
            out.writeByte(1 << 4);
            writeRemaining(out, remaining);
            writeUtf8(out, "MQTT");
            out.writeByte(4); // protocol level 3.1.1
            out.writeByte(connectFlags);
            out.writeShort(c.getKeepAlive());
            writeUtf8(out, c.getClientId());
            if (hasUser) {
                writeUtf8(out, c.getUsername());
            }
            if (hasPass) {
                writeUtf8(out, c.getPassword());
            }
        } else if (msg instanceof MqttMessage.Publish p) {
            byte[] topic = p.getTopic().getBytes(StandardCharsets.UTF_8);
            int remaining = 2 + topic.length + p.getPayload().length;
            if (p.getQos() > 0) {
                remaining += 2;
            }
            int flags = (p.getFlags() & 0x0F);
            out.writeByte((3 << 4) | flags);
            writeRemaining(out, remaining);
            writeUtf8(out, p.getTopic());
            if (p.getQos() > 0) {
                out.writeShort(p.getPacketId());
            }
            out.writeBytes(p.getPayload());
        } else if (msg instanceof MqttMessage.Subscribe s) {
            int remaining = 2; // packetId
            for (String t : s.getTopics()) {
                remaining += 2 + t.getBytes(StandardCharsets.UTF_8).length + 1;
            }
            out.writeByte((8 << 4) | 0x02);
            writeRemaining(out, remaining);
            out.writeShort(s.getPacketId());
            for (String t : s.getTopics()) {
                writeUtf8(out, t);
                out.writeByte(0); // 请求 QoS 0
            }
        } else if (msg instanceof MqttMessage.PingReq) {
            out.writeByte(12 << 4);
            writeRemaining(out, 0);
        } else if (msg instanceof MqttMessage.Disconnect) {
            out.writeByte(14 << 4);
            writeRemaining(out, 0);
        }
    }

    private void writeUtf8(ByteBuf out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(b.length);
        out.writeBytes(b);
    }

    private void writeRemaining(ByteBuf out, int remaining) {
        int v = remaining;
        do {
            int b = v & 0x7F;
            v >>>= 7;
            if (v > 0) {
                b |= 0x80;
            }
            out.writeByte(b);
        } while (v > 0);
    }
}
