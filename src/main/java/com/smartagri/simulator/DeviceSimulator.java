package com.smartagri.simulator;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内置（Java/Netty）设备模拟器：作为 Python 脚本不可用时的兜底实现。
 * 默认 500 台设备、1000 个并发连接，携带模拟器服务账号凭证以通过鉴权。
 */
@Component
public class DeviceSimulator {

    private static final Logger log = LoggerFactory.getLogger(DeviceSimulator.class);

    @Value("${mqtt.port:1883}")
    private int port;
    @Value("${mqtt.auth.simulator-username:simulator}")
    private String simUsername;
    @Value("${mqtt.auth.simulator-secret:sim-secret-2026-change-me}")
    private String simSecret;

    private final String host = "127.0.0.1";
    private NioEventLoopGroup group;
    private final ConcurrentHashMap<String, Channel> active = new ConcurrentHashMap<>();
    private final AtomicInteger deviceCount = new AtomicInteger(0);
    private volatile boolean running = false;

    public synchronized void start(int devices, int connections) {
        if (running) {
            stop();
        }
        group = new NioEventLoopGroup();
        deviceCount.set(devices);
        int keepAliveMs = 15000;
        int telemetryIntervalMs = 2000;
        for (int i = 0; i < connections; i++) {
            String deviceId = "device-" + (i % devices);
            String connId = "sim-java-" + i;
            connectOne(connId, deviceId, keepAliveMs, telemetryIntervalMs, 0);
        }
        running = true;
        log.info("✅ 内置模拟器启动：{} 台设备 / {} 个并发连接，目标端口 {}", devices, connections, port);
    }

    private void connectOne(String connId, String deviceId, int keepAliveMs, int telemetryIntervalMs, int attempt) {
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new com.smartagri.mqtt.codec.MqttDecoder(),
                                new com.smartagri.mqtt.codec.MqttEncoder(),
                                new SimClientHandler(connId, deviceId, keepAliveMs,
                                        telemetryIntervalMs, simUsername, simSecret));
                    }
                });
        b.connect(host, port).addListener((ChannelFuture f) -> {
            if (f.isSuccess()) {
                active.put(f.channel().id().asLongText(), f.channel());
            } else {
                if (attempt < 8) {
                    group.schedule(() -> connectOne(connId, deviceId, keepAliveMs, telemetryIntervalMs, attempt + 1),
                            1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                } else {
                    log.warn("模拟设备 {} 连接失败（已放弃重试）", deviceId);
                }
            }
        });
    }

    public synchronized void stop() {
        for (Channel ch : active.values()) {
            ch.close();
        }
        active.clear();
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
        running = false;
        log.info("模拟器已停止");
    }

    public boolean isRunning() {
        return running;
    }

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("running", running);
        s.put("activeConnections", active.size());
        s.put("devices", deviceCount.get());
        return s;
    }
}
