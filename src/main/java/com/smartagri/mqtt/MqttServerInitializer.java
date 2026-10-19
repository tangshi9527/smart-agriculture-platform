package com.smartagri.mqtt;

import com.smartagri.command.CommandService;
import com.smartagri.device.DeviceKeyRepository;
import com.smartagri.ingest.IngestGateway;
import com.smartagri.mqtt.codec.MqttDecoder;
import com.smartagri.mqtt.codec.MqttEncoder;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;

/**
 * Netty 管道初始化：解码器 -> 编码器 -> 业务处理器。
 */
public class MqttServerInitializer extends ChannelInitializer<SocketChannel> {

    private final IngestGateway ingest;
    private final DeviceKeyRepository keyRepository;
    private final CommandService commandService;
    private final boolean authEnabled;
    private final String simUsername;
    private final String simSecret;

    public MqttServerInitializer(IngestGateway ingest, DeviceKeyRepository keyRepository,
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
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast(new MqttDecoder());
        p.addLast(new MqttEncoder());
        p.addLast(new MqttInboundHandler(ingest, keyRepository, commandService,
                authEnabled, simUsername, simSecret));
    }
}
