package com.smartagri.mqtt.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MQTT 3.1.1 二进制协议解码器。
 * 基于 Netty 的 ByteToMessageDecoder 实现：先解析固定头与可变剩余长度，
 * 待整包到达后再做二进制解析（长连接报文可能分片到达）。
 */
public class MqttDecoder extends ByteToMessageDecoder {

    /**
     * 单条报文剩余长度上限，<b>唯一</b>一条线。
     *
     * <p>没有这个上限时，设备只要发一个声明 256MB 剩余长度的 PUBLISH（变长整数最大 4 字节 = 268,435,455），
     * {@code if (in.readableBytes() < total) return;} 就会让 Netty 的累积缓冲区一路涨到 256MB 才放行——
     * 这是唯一一条<b>设备侧能主动把服务端打死</b>的路径，与 topic ACL 无关（ACL 只看 topic，不看大小）。</p>
     *
     * <p>上一版这里写了两条线：整包 512KB、载荷 256KB。两条线之间那 256KB 的差不是「更宽松」，
     * 是没想清楚谁该管——载荷本身就是剩余长度减去可变头，{@code payloadLen <= remainingLength}
     * 恒成立。所以只要卡住剩余长度，载荷自然被卡住，第二条线永远不可能先触发，
     * 它唯一的作用是让读代码的人以为有两个独立阈值。留一条。</p>
     *
     * <p>农业遥测一帧 1KB 足够，256KB 已经非常宽松。</p>
     */
    public static final int MAX_REMAINING_LENGTH = 256 * 1024;

    /**
     * 单个 UTF-8 字符串字段（协议名 / clientId / 用户名 / 密码 / topic）的长度上限。
     *
     * <p>MQTT 用 uint16 表示这类字段长度，理论上可以声明 65535 字节。{@code new byte[len]}
     * 会按声明值直接分配——这是「声明大、实际小」的经典构造：一帧只有 20 字节，
     * 但每个字段都声明 65535，解码器就按声明值分配。现在有了帧长上限，
     * 单帧实际占用已经被关在 256KB 内，但校验字段级声明仍然有价值：
     * 它能给出一条说明白的错误，而不是 {@code getBytes} 越界抛一个看不懂的
     * {@code IndexOutOfBoundsException}。</p>
     */
    public static final int MAX_STRING_FIELD = 8 * 1024;

    /** 单条 SUBSCRIBE 允许的 topic filter 个数上限（真实设备是 1~3 个） */
    public static final int MAX_TOPIC_FILTERS = 64;

    private static final Logger log = LoggerFactory.getLogger(MqttDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 2) {
            return;
        }
        int base = in.readerIndex();
        int b0 = in.getByte(base) & 0xFF;
        int type = (b0 >> 4) & 0x0F;
        int flags = b0 & 0x0F;

        // 解析剩余长度（变长整数，最多 4 字节）
        long rem = 0;
        int multiplier = 1;
        int lenBytes = 0;
        for (int i = 0; i < 4; i++) {
            if (base + 1 + i >= in.writerIndex()) {
                return; // 长度字节未到齐
            }
            int b = in.getByte(base + 1 + i) & 0xFF;
            rem += (long) (b & 0x7F) * multiplier;
            multiplier *= 128;
            lenBytes++;
            if ((b & 0x80) == 0) {
                break;
            }
            if (lenBytes == 4) {
                throw new IllegalArgumentException("剩余长度编码非法");
            }
        }
        int remainingLength = (int) rem;
        if (remainingLength > MAX_REMAINING_LENGTH) {
            // 抛标准帧过长异常：由 MqttInboundHandler.exceptionCaught 关闭连接，避免继续累积
            throw new io.netty.handler.codec.TooLongFrameException(
                    "MQTT 报文超过上限: remainingLength=" + remainingLength + " > " + MAX_REMAINING_LENGTH);
        }
        int total = 1 + lenBytes + remainingLength;
        if (in.readableBytes() < total) {
            return; // 整包未到齐，等待下次累积
        }

        MqttMessage msg = parse(in, base, type, flags, lenBytes, remainingLength);
        in.readerIndex(base + total); // 消费整包
        if (msg != null) {
            out.add(msg);
        }
    }

    private MqttMessage parse(ByteBuf in, int base, int type, int flags,
                             int lenBytes, int remainingLength) {
        int pos = base + 1 + lenBytes;          // 可变头起始
        int end = pos + remainingLength;        // 包尾（注意不是 base + 1 + lenBytes + remainingLength，等价但不易读错）

        switch (type) {
            case 1: { // CONNECT
                int protoLen = fieldLen(in, pos, end, "protocol");
                String proto = readUtf8(in, pos, protoLen);
                pos += 2 + protoLen;
                int level = in.getByte(pos++) & 0xFF;
                int connectFlags = in.getByte(pos++) & 0xFF;
                int keepAlive = in.getUnsignedShort(pos);
                pos += 2;
                int clientIdLen = fieldLen(in, pos, end, "clientId");
                String clientId = readUtf8(in, pos, clientIdLen);
                pos += 2 + clientIdLen;
                String username = null;
                String password = null;
                if ((connectFlags & 0x80) != 0) { // 含用户名
                    int len = fieldLen(in, pos, end, "username");
                    username = readUtf8(in, pos, len);
                    pos += 2 + len;
                }
                if ((connectFlags & 0x40) != 0) { // 含密码
                    int len = fieldLen(in, pos, end, "password");
                    password = readUtf8(in, pos, len);
                    pos += 2 + len;
                }
                if (!"MQTT".equals(proto) || level != 4) {
                    log.warn("不支持的协议: proto={}, level={}", proto, level);
                    return null;
                }
                boolean clean = (connectFlags & 0x02) != 0;
                return new MqttMessage.Connect(flags, clientId, keepAlive, clean, username, password);
            }
            case 3: { // PUBLISH
                int topicLen = fieldLen(in, pos, end, "topic");
                String topic = readUtf8(in, pos, topicLen);
                pos += 2 + topicLen;
                int qos = (flags >> 1) & 0x03;
                int packetId = 0;
                if (qos > 0) {
                    packetId = in.getUnsignedShort(pos);
                    pos += 2;
                }
                // 载荷 = 剩余长度减去可变头，一定 <= remainingLength，已经被 MAX_REMAINING_LENGTH 卡住。
                // 这里只需要保证 pos 没越过 end（畸形 QoS 标志会让 pos 多走 2 字节）。
                int payloadLen = end - pos;
                if (payloadLen < 0) {
                    throw new IllegalArgumentException("PUBLISH 可变头超出报文长度: pos=" + pos + " end=" + end);
                }
                byte[] payload = new byte[payloadLen];
                if (payloadLen > 0) {
                    in.getBytes(pos, payload);
                }
                return new MqttMessage.Publish(flags, topic, packetId, payload);
            }
            case 8: { // SUBSCRIBE
                if (pos + 2 > end) {
                    throw new IllegalArgumentException("SUBSCRIBE 缺少 packetId");
                }
                int packetId = in.getUnsignedShort(pos);
                pos += 2;
                List<String> topics = new ArrayList<>();
                // 循环条件用 pos + 3 <= end：每个 filter 至少占 3 字节（2 字节长度 + 1 字节 QoS），
                // 用 pos < end 会让最后半截进入循环体，靠内部越界异常收场
                while (pos + 3 <= end) {
                    if (topics.size() >= MAX_TOPIC_FILTERS) {
                        throw new IllegalArgumentException(
                                "SUBSCRIBE 的 topic filter 超过上限: " + MAX_TOPIC_FILTERS);
                    }
                    int len = fieldLen(in, pos, end, "topicFilter");
                    topics.add(readUtf8(in, pos, len));
                    pos += 2 + len;
                    pos += 1; // 跳过 QoS 字节
                }
                return new MqttMessage.Subscribe(flags, packetId, topics);
            }
            case 12:
                return new MqttMessage.PingReq();
            case 14:
                return new MqttMessage.Disconnect();
            default:
                log.debug("忽略未实现报文类型: {}", type);
                return null;
        }
    }

    /**
     * 校验并返回一个 uint16 长度前缀字段的字节长度。
     *
     * <p>两个必须在这里拦的判断：</p>
     * <ol>
     *   <li><b>声明长度超出本帧剩余</b>——「声明大、实际小」的构造。不拦会走到
     *       {@code in.getBytes(offset + 2, b)} 越界读取。</li>
     *   <li><b>声明长度超过字段上限</b>——{@code new byte[len]} 会按声明值直接分配。</li>
     * </ol>
     */
    private int fieldLen(ByteBuf in, int offset, int end, String field) {
        if (offset + 2 > end) {
            throw new IllegalArgumentException(
                    "MQTT 字段截断，缺少长度前缀: field=" + field + " offset=" + offset + " end=" + end);
        }
        int len = in.getUnsignedShort(offset);
        if (len > MAX_STRING_FIELD) {
            throw new IllegalArgumentException(
                    "MQTT 字段声明长度超过上限: field=" + field + " declared=" + len + " > " + MAX_STRING_FIELD);
        }
        if (offset + 2 + len > end) {
            throw new IllegalArgumentException(
                    "MQTT 字段声明长度超出报文实际长度: field=" + field
                            + " declared=" + len + " available=" + (end - offset - 2));
        }
        return len;
    }

    /**
     * 按<b>字节长度</b>读取 UTF-8 字段。
     *
     * <p>上一版用 {@code pos += 2 + t.length()} 推进位置——{@code String.length()} 是字符数，
     * 不是字节数。中文 topic（如 {@code 设备/一号棚/telemetry}）每个字 3 字节，
     * 用字符数推进会让 pos 落在报文中间，后续字段全部读错位：topic 解析出乱码、
     * 载荷切错、甚至读到别的字段里去。MQTT 线上是字节，所有位置推进都必须用字节长度。</p>
     */
    private String readUtf8(ByteBuf in, int offset, int len) {
        byte[] b = new byte[len];
        if (len > 0) {
            in.getBytes(offset + 2, b);
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
