package com.smartagri.mqtt;

import com.smartagri.command.CommandService;
import com.smartagri.device.DeviceKeyRepository;
import com.smartagri.ingest.IngestGateway;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 基于 Netty 的高性能 MQTT 接入服务：支撑多传感器长连接管理、
 * 心跳保活与二进制协议解析，设计目标 500 台设备 / 1000+ 并发连接稳定接入。
 */
@Component
public class MqttServer {

    private static final Logger log = LoggerFactory.getLogger(MqttServer.class);

    private final IngestGateway ingest;
    private final DeviceKeyRepository keyRepository;
    private final CommandService commandService;

    @Value("${mqtt.port:1883}")
    private int port;
    @Value("${mqtt.auth.enabled:true}")
    private boolean authEnabled;
    @Value("${mqtt.auth.simulator-username:simulator}")
    private String simUsername;
    @Value("${mqtt.auth.simulator-secret:sim-secret-2026-change-me}")
    private String simSecret;

    private NioEventLoopGroup boss;
    private NioEventLoopGroup worker;

    public MqttServer(IngestGateway ingest, DeviceKeyRepository keyRepository,
                     CommandService commandService) {
        this.ingest = ingest;
        this.keyRepository = keyRepository;
        this.commandService = commandService;
    }

    @PostConstruct
    public void start() {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 4096)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new MqttServerInitializer(ingest, keyRepository, commandService,
                            authEnabled, simUsername, simSecret));
            b.bind(port).sync();
            log.info("✅ MQTT 接入层已启动，监听端口 {}", port);
        } catch (Exception e) {
            log.error("❌ MQTT 接入层启动失败（端口 {} 可能被占用）: {}", port, e.getMessage());
            stop();
        }
    }

    @PreDestroy
    public void stop() {
        if (boss != null) {
            boss.shutdownGracefully();
        }
        if (worker != null) {
            worker.shutdownGracefully();
        }
        log.info("MQTT 接入层已停止");
    }
}
