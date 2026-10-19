package com.smartagri.mqtt.session;

import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 设备长连接注册表：以连接(Channel) 为粒度维护并发连接集合，支撑心跳保活与资源清理。
 * 设备身份由遥测 topic 中的 deviceId 决定（连接 ID 仅标识一条 TCP 会话），
 * 因此可支持「多连接复用同一设备」或「连接数 &gt; 设备数」的场景（本机模拟器验证到 1000 连接 / 500 设备）。
 */
@Service
public class DeviceSessionRegistry {

    /** connId(channel long id) -> Channel */
    private final ConcurrentHashMap<String, Channel> connections = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    public void register(String connId, Channel ch) {
        connections.put(connId, ch);
        connectionCount.incrementAndGet();
    }

    public void unregister(String connId, Channel ch) {
        connections.remove(connId);
        connectionCount.decrementAndGet();
    }

    public int connectionCount() {
        return Math.max(connectionCount.get(), 0);
    }
}
