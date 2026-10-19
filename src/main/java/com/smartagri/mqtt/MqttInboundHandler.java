package com.smartagri.mqtt;

import com.smartagri.command.CommandService;
import com.smartagri.common.Constants;
import com.smartagri.device.DeviceKey;
import com.smartagri.device.DeviceKeyRepository;
import com.smartagri.ingest.IngestGateway;
import com.smartagri.mqtt.codec.MqttDecoder;
import com.smartagri.mqtt.codec.MqttEncoder;
import com.smartagri.mqtt.codec.MqttMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * MQTT 入站业务处理器：连接鉴权、心跳保活、发布/订阅响应。
 * 每个连接一个实例（在 Initializer 中创建），因此可用实例字段保存 deviceId。
 */
public class MqttInboundHandler extends SimpleChannelInboundHandler<MqttMessage> {

    private static final Logger log = LoggerFactory.getLogger(MqttInboundHandler.class);

    private final IngestGateway ingest;
    private final DeviceKeyRepository keyRepository;
    private final CommandService commandService;
    private final boolean authEnabled;
    private final String simUsername;
    private final String simSecret;

    /** 本连接绑定的设备 ID（真实设备 = CONNECT 的 username）；模拟器账号为 null */
    private String boundDeviceId;
    /** 本连接是否以模拟器服务账号登录（一进程模拟多设备，所以放宽 topic 限制） */
    private boolean simulatorAccount;

    public MqttInboundHandler(IngestGateway ingest, DeviceKeyRepository keyRepository,
                             CommandService commandService,
                             boolean authEnabled, String simUsername, String simSecret) {
        this.ingest = ingest;
        this.keyRepository = keyRepository;
        this.commandService = commandService;
        this.authEnabled = authEnabled;
        this.simUsername = simUsername;
        this.simSecret = simSecret;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MqttMessage msg) {
        switch (msg.getType()) {
            case 1 -> { // CONNECT
                MqttMessage.Connect c = (MqttMessage.Connect) msg;
                if (!authenticate(c)) {
                    // 鉴权失败：返回拒绝码(4=用户名/密码错误)并关闭连接
                    ctx.writeAndFlush(new MqttMessage.ConnAck(4)).addListener(f -> ctx.close());
                    log.warn("设备鉴权失败，拒绝连接 clientId={}, username={}",
                            c.getClientId(), c.getUsername());
                    return;
                }
                // 绑定本连接的身份：真实设备 username 即 deviceId；模拟器账号可跨设备
                bindIdentity(c);
                ingest.onConnect(ctx.channel());
                ctx.writeAndFlush(new MqttMessage.ConnAck(0));
            }
            case 3 -> { // PUBLISH
                MqttMessage.Publish p = (MqttMessage.Publish) msg;
                String topic = p.getTopic();
                if (!canPublish(topic)) {
                    // topic 越权（用 A 设备的身份往 B 设备上报数据）：直接断开，不入库、不回 PUBACK
                    log.warn("topic 越权，已断开连接 boundDevice={} topic={}", boundDeviceId, topic);
                    ctx.close();
                    return;
                }
                if (topic != null && topic.endsWith("/command/response")) {
                    // 设备执行回执：交给指令服务闭环
                    commandService.onResponse(extractDeviceIdFromResponse(topic), p.getPayload());
                } else {
                    ingest.onTelemetry(ctx.channel(), topic, p.getPayload());
                }
                if (p.getQos() > 0) {
                    ctx.writeAndFlush(new MqttMessage.PubAck(p.getPacketId()));
                }
            }
            case 8 -> { // SUBSCRIBE
                MqttMessage.Subscribe s = (MqttMessage.Subscribe) msg;
                List<Integer> codes = new ArrayList<>();
                for (String t : s.getTopics()) {
                    // 0x80 = 订阅失败；真实设备只允许订阅自己名下的 topic
                    codes.add(canSubscribe(t) ? 0 : 0x80);
                }
                ctx.writeAndFlush(new MqttMessage.SubAck(s.getPacketId(), codes));
            }
            case 12 -> { // PINGREQ（心跳）
                ingest.onPing(ctx.channel());
                ctx.writeAndFlush(new MqttMessage.PingResp());
            }
            case 14 -> { // DISCONNECT
                ingest.onDisconnect(ctx.channel());
            }
            default -> { /* ignore */ }
        }
    }

    /** 连接鉴权：关闭则放行；否则模拟器服务账号或单设备密钥二选一 */
    private boolean authenticate(MqttMessage.Connect c) {
        if (!authEnabled) {
            return true;
        }
        String user = c.getUsername();
        String pass = c.getPassword();
        // 模拟器服务账号
        if (constantTimeEquals(simUsername, user) && constantTimeEquals(simSecret, pass)) {
            return true;
        }
        // 单设备密钥：username 即 deviceId
        if (user != null) {
            DeviceKey k = keyRepository.findById(user).orElse(null);
            if (k != null && "ENABLED".equals(k.getStatus())
                    && constantTimeEquals(k.getSecret(), pass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 记录本连接的身份。模拟器账号可跨设备（一个进程模拟上百台），真实设备只能操作自己名下 topic。
     * 包可见是为了让 ACL 能被单测直接验证，不必真的起一条 TCP 连接。
     */
    void bindIdentity(MqttMessage.Connect c) {
        this.simulatorAccount = authEnabled && simUsername.equals(c.getUsername());
        this.boundDeviceId = simulatorAccount ? null : c.getUsername();
    }

    /**
     * 发布权限：带方向的白名单，不是简单的"前缀匹配"。
     *
     * <p>MQTT 的 topic 是有方向的，同一个 {@code device/{id}/} 前缀下混着上行和下行：</p>
     * <ul>
     *   <li>{@code device/{id}/telemetry} 上行，设备 → 服务器</li>
     *   <li>{@code device/{id}/command/response} 上行，设备 → 服务器（执行回执）</li>
     *   <li>{@code device/{id}/command} <b>下行</b>，服务器 → 设备</li>
     * </ul>
     * <p>只做前缀匹配的话，设备可以 PUBLISH 自己的 command 主题（会掉进 onTelemetry 当遥测处理，
     * 语义污染），也可以 SUBSCRIBE 自己的 telemetry（自发自收，模拟器一个 bug 就能打出回环）。
     * 因此这里按 (action, topic) 精确放行，其余一律拒绝。</p>
     */
    boolean canPublish(String topic) {
        if (topic == null) {
            return false;
        }
        if (!authEnabled) {
            return true;
        }
        if (simulatorAccount) {
            return topic.startsWith("device/");
        }
        if (boundDeviceId == null) {
            return true;
        }
        // 设备只允许「上行」两种主题
        return topic.equals(String.format(Constants.TOPIC_TELEMETRY, boundDeviceId))
                || topic.equals(String.format(Constants.TOPIC_COMMAND_RESPONSE, boundDeviceId));
    }

    /**
     * 订阅权限：设备只允许订阅「下行」的指令主题。
     *
     * <p>允许 {@code device/{id}/command} 及其子主题（如 {@code command/ack}），
     * 但<strong>排除</strong> {@code command/response}——那是设备自己上行的回执主题，
     * 允许订阅等于自己发自己收，一个模拟器 bug 就能造成回环。</p>
     */
    boolean canSubscribe(String topic) {
        if (topic == null) {
            return false;
        }
        if (!authEnabled || simulatorAccount || boundDeviceId == null) {
            return true;
        }
        String commandTopic = String.format(Constants.TOPIC_COMMAND, boundDeviceId);
        String responseTopic = String.format(Constants.TOPIC_COMMAND_RESPONSE, boundDeviceId);
        if (topic.equals(responseTopic)) {
            return false;
        }
        return topic.equals(commandTopic) || topic.startsWith(commandTopic + "/");
    }

    /** 先各自 SHA-256 再比较，保证长度维度也恒定（见方法内注释） */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        // MessageDigest.isEqual 内部第一步是比长度，直接比原文会把「密钥长度」泄露给时序侧信道。
        // 摘要长度恒为 32 字节，比摘要才是真正恒定的。
        return java.security.MessageDigest.isEqual(sha256(expected), sha256(actual));
    }

    private static byte[] sha256(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ingest.onDisconnect(ctx.channel());
        ctx.fireChannelInactive();
    }

    /** 从回执 topic（device/{deviceId}/command/response）提取设备 ID */
    private String extractDeviceIdFromResponse(String topic) {
        String prefix = "device/";
        String suffix = "/command/response";
        if (topic.startsWith(prefix) && topic.endsWith(suffix)) {
            return topic.substring(prefix.length(), topic.length() - suffix.length());
        }
        return null;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("连接异常: {}", cause.getMessage());
        ctx.close();
    }
}
