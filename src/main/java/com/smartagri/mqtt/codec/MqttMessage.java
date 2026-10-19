package com.smartagri.mqtt.codec;

import java.util.List;

/**
 * MQTT 3.1.1 报文对象模型（仅实现本项目所需的报文类型）。
 * 所有报文均为纯 Java 对象，二进制解析在 MqttDecoder 中完成，避免 ByteBuf 引用计数问题。
 */
public abstract class MqttMessage {
    private final int type;   // 报文类型码
    private final int flags;  // 低 4 位标志

    protected MqttMessage(int type, int flags) {
        this.type = type;
        this.flags = flags;
    }

    public int getType() { return type; }
    public int getFlags() { return flags; }

    /** 客户端 -> 服务端：连接请求 */
    public static class Connect extends MqttMessage {
        private final String clientId;
        private final int keepAlive;
        private final boolean cleanSession;
        private final String username;
        private final String password;

        public Connect(int flags, String clientId, int keepAlive, boolean cleanSession,
                      String username, String password) {
            super(1, flags);
            this.clientId = clientId;
            this.keepAlive = keepAlive;
            this.cleanSession = cleanSession;
            this.username = username;
            this.password = password;
        }
        public String getClientId() { return clientId; }
        public int getKeepAlive() { return keepAlive; }
        public boolean isCleanSession() { return cleanSession; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
    }

    /** 客户端 -> 服务端：发布遥测（二进制载荷，本项目用 JSON 承载传感器读数） */
    public static class Publish extends MqttMessage {
        private final String topic;
        private final int packetId;
        private final byte[] payload;

        public Publish(int flags, String topic, int packetId, byte[] payload) {
            super(3, flags);
            this.topic = topic;
            this.packetId = packetId;
            this.payload = payload;
        }
        public String getTopic() { return topic; }
        public int getPacketId() { return packetId; }
        public byte[] getPayload() { return payload; }
        public int getQos() { return (getFlags() >> 1) & 0x03; }
    }

    /** 客户端 -> 服务端：订阅 */
    public static class Subscribe extends MqttMessage {
        private final int packetId;
        private final List<String> topics;

        public Subscribe(int flags, int packetId, List<String> topics) {
            super(8, flags);
            this.packetId = packetId;
            this.topics = topics;
        }
        public int getPacketId() { return packetId; }
        public List<String> getTopics() { return topics; }
    }

    public static class PingReq extends MqttMessage {
        public PingReq() { super(12, 0); }
    }

    public static class Disconnect extends MqttMessage {
        public Disconnect() { super(14, 0); }
    }

    /** 服务端 -> 客户端：连接确认 */
    public static class ConnAck extends MqttMessage {
        private final int returnCode;
        public ConnAck(int returnCode) { super(2, 0); this.returnCode = returnCode; }
        public int getReturnCode() { return returnCode; }
    }

    /** 服务端 -> 客户端：订阅确认 */
    public static class SubAck extends MqttMessage {
        private final int packetId;
        private final List<Integer> returnCodes;
        public SubAck(int packetId, List<Integer> returnCodes) { super(9, 0); this.packetId = packetId; this.returnCodes = returnCodes; }
        public int getPacketId() { return packetId; }
        public List<Integer> getReturnCodes() { return returnCodes; }
    }

    /** 服务端 -> 客户端：心跳响应 */
    public static class PingResp extends MqttMessage {
        public PingResp() { super(13, 0); }
    }

    /** 服务端 -> 客户端：发布确认（QoS1） */
    public static class PubAck extends MqttMessage {
        private final int packetId;
        public PubAck(int packetId) { super(4, 0); this.packetId = packetId; }
        public int getPacketId() { return packetId; }
    }
}
