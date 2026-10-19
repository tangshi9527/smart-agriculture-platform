package com.smartagri.simulator;

import com.smartagri.common.Constants;
import com.smartagri.common.JsonUtil;
import com.smartagri.mqtt.codec.MqttMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 内置模拟设备客户端处理器（Java/Netty 兜底实现）：连接后周期性上报遥测并发送心跳，
 * 同时订阅云端下行控制指令、执行并回执，以支撑「执行控制闭环」。
 * 携带模拟器服务账号的 username/password 以通过接入层鉴权。
 */
public class SimClientHandler extends SimpleChannelInboundHandler<MqttMessage> {

    private final String connId;
    private final String deviceId;
    private final int keepAliveMs;
    private final int telemetryIntervalMs;
    private final String username;
    private final String password;
    private final Random rnd = new Random();

    /** 执行器本地状态：风扇/灌溉阀/补光灯/水泵（0=关 1=开） */
    private final Map<String, Integer> controls = new LinkedHashMap<>();

    private ScheduledFuture<?> telemetryTask;
    private ScheduledFuture<?> pingTask;
    private int subPacketId = 1;

    public SimClientHandler(String connId, String deviceId, int keepAliveMs,
                           int telemetryIntervalMs, String username, String password) {
        this.connId = connId;
        this.deviceId = deviceId;
        this.keepAliveMs = keepAliveMs;
        this.telemetryIntervalMs = telemetryIntervalMs;
        this.username = username;
        this.password = password;
        controls.put("fan", 0);
        controls.put("valve", 0);
        controls.put("lamp", 0);
        controls.put("pump", 0);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 发送 CONNECT（clean session，携带模拟器服务账号凭证）
        ctx.writeAndFlush(new MqttMessage.Connect(0x02, connId, keepAliveMs / 1000, true, username, password));
        // 订阅云端下行控制指令主题（控制闭环）
        ctx.writeAndFlush(new MqttMessage.Subscribe(2, subPacketId++,
                Arrays.asList(String.format(Constants.TOPIC_COMMAND, deviceId))));
        telemetryTask = ctx.executor().scheduleAtFixedRate(
                () -> sendTelemetry(ctx), 500, telemetryIntervalMs, TimeUnit.MILLISECONDS);
        pingTask = ctx.executor().scheduleAtFixedRate(
                () -> ctx.writeAndFlush(new MqttMessage.PingReq()),
                keepAliveMs, keepAliveMs, TimeUnit.MILLISECONDS);
    }

    private void sendTelemetry(ChannelHandlerContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        double temp = 18 + rnd.nextDouble() * 15;          // 18~33℃
        if (rnd.nextDouble() < 0.04) {
            temp += 12;                                    // 偶发高温，触发告警
        }
        m.put("temperature", round(temp));
        m.put("humidity", round(50 + rnd.nextDouble() * 40));
        m.put("soilMoisture", round(30 + rnd.nextDouble() * 50));
        m.put("light", Math.round(rnd.nextDouble() * 100000));
        m.put("co2", round(400 + rnd.nextDouble() * 800));
        m.put("ph", round(6 + rnd.nextDouble() * 1.5));
        // 携带执行器开关状态，使云端影子持续反映设备真实状态
        m.putAll(controls);
        String json = JsonUtil.toJson(m);
        MqttMessage.Publish p = new MqttMessage.Publish(
                0, "device/" + deviceId + "/telemetry", 0, json.getBytes(StandardCharsets.UTF_8));
        ctx.writeAndFlush(p);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MqttMessage msg) {
        // 客户端无需处理服务端下行报文（CONNACK/SUBACK/PINGRESP 直接忽略），
        // 但需处理云端下发的控制指令（PUBLISH 至 device/{id}/command）
        if (msg instanceof MqttMessage.Publish p) {
            String topic = p.getTopic();
            if (topic != null && topic.endsWith("/command")) {
                handleCommand(ctx, p);
            }
        }
    }

    /** 执行云端下发的控制动作并回执 */
    private void handleCommand(ChannelHandlerContext ctx, MqttMessage.Publish p) {
        try {
            Map<String, Object> cmd = JsonUtil.fromJson(
                    new String(p.getPayload(), StandardCharsets.UTF_8), Map.class);
            String action = cmd.get("action") == null ? null : String.valueOf(cmd.get("action"));
            String cmdId = cmd.get("cmdId") == null ? null : String.valueOf(cmd.get("cmdId"));
            boolean ok = executeAction(action);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("cmdId", cmdId);
            resp.put("deviceId", deviceId);
            resp.put("action", action);
            resp.put("status", ok ? "OK" : "UNSUPPORTED");
            resp.put("state", new LinkedHashMap<>(controls));
            if (!ok) {
                resp.put("message", "不支持的动作: " + action);
            }
            byte[] bytes = JsonUtil.toJson(resp).getBytes(StandardCharsets.UTF_8);
            String respTopic = String.format(Constants.TOPIC_COMMAND_RESPONSE, deviceId);
            ctx.writeAndFlush(new MqttMessage.Publish(0, respTopic, 0, bytes));
        } catch (Exception e) {
            // 解析失败则忽略
        }
    }

    /** 解析动作并更新本地执行器状态；返回是否识别 */
    private boolean executeAction(String action) {
        if (action == null) {
            return false;
        }
        String up = action.toUpperCase();
        Integer on;
        if (up.endsWith("_ON") || up.endsWith("_OPEN")) {
            on = 1;
        } else if (up.endsWith("_OFF") || up.endsWith("_CLOSE")) {
            on = 0;
        } else {
            return false;
        }
        String prefix = up.split("_")[0];
        String name = switch (prefix) {
            case "FAN" -> "fan";
            case "VALVE" -> "valve";
            case "LAMP" -> "lamp";
            case "PUMP" -> "pump";
            default -> null;
        };
        if (name == null) {
            return false;
        }
        controls.put(name, on);
        return true;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (telemetryTask != null) {
            telemetryTask.cancel(false);
        }
        if (pingTask != null) {
            pingTask.cancel(false);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
