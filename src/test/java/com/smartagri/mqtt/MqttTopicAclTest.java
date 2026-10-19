package com.smartagri.mqtt;

import com.smartagri.mqtt.codec.MqttMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQTT topic 级 ACL：修复「任何有效设备都能往别人的 topic 发数据」。
 *
 * <p>修复前的行为是：只要 CONNECT 通过鉴权，PUBLISH 的 topic 完全不校验，
 * 设备 A 可以直接发 {@code device/B/telemetry} 伪造设备 B 的读数，告警与历史数据全部被污染。</p>
 */
class MqttTopicAclTest {

    private static final String SIM_USER = "simulator";
    private static final String SIM_SECRET = "sim-secret";

    private MqttInboundHandler handler(boolean authEnabled) {
        // 依赖方法在这两个用例里不会被触发，传 null 即可
        return new MqttInboundHandler(null, null, null, authEnabled, SIM_USER, SIM_SECRET);
    }

    private MqttMessage.Connect connect(String username) {
        return new MqttMessage.Connect(0, "client-" + username, 60, true, username, "x");
    }

    @Test
    void realDeviceCanPublishOwnTopicsOnly() {
        MqttInboundHandler h = handler(true);
        h.bindIdentity(connect("device-00001"));

        assertTrue(h.canPublish("device/device-00001/telemetry"));
        assertTrue(h.canPublish("device/device-00001/command/response"));

        // 核心断言：不能伪造别的设备
        assertFalse(h.canPublish("device/device-00002/telemetry"));
        assertFalse(h.canPublish("device/device-00002/command/response"));
        assertFalse(h.canPublish("device/../device-00002/telemetry"));
        assertFalse(h.canPublish(null));
    }

    @Test
    void realDeviceCanSubscribeOwnCommandTopicOnly() {
        MqttInboundHandler h = handler(true);
        h.bindIdentity(connect("device-00001"));

        assertTrue(h.canSubscribe("device/device-00001/command"));
        assertFalse(h.canSubscribe("device/device-00002/command"));
    }

    /** 方向性：设备不能发布「下行」主题，也不能订阅「上行」主题（避免自发自收回环） */
    @Test
    void directionIsEnforced() {
        MqttInboundHandler h = handler(true);
        h.bindIdentity(connect("device-00001"));

        // 上行合法
        assertTrue(h.canPublish("device/device-00001/telemetry"));
        assertTrue(h.canPublish("device/device-00001/command/response"));
        // 下行主题不允许设备发布（否则会掉进 onTelemetry 被当遥测处理，语义污染）
        assertFalse(h.canPublish("device/device-00001/command"));
        // 设备不订阅自己的上行主题
        assertFalse(h.canSubscribe("device/device-00001/telemetry"));
        assertFalse(h.canSubscribe("device/device-00001/command/response"));
        // 子主题形式的指令订阅允许
        assertTrue(h.canSubscribe("device/device-00001/command/ack"));
    }

    @Test
    void simulatorAccountKeepsCrossDeviceAbility() {
        MqttInboundHandler h = handler(true);
        h.bindIdentity(connect(SIM_USER));

        // 一个模拟器进程模拟上百台设备，必须允许跨设备，否则演示直接跑不起来
        assertTrue(h.canPublish("device/device-00001/telemetry"));
        assertTrue(h.canPublish("device/device-00999/telemetry"));
        assertTrue(h.canSubscribe("device/device-00001/command"));
        // 但仍要求 topic 形态合法
        assertFalse(h.canPublish("system/shutdown"));
    }

    @Test
    void authDisabledFallsBackToOpenMode() {
        MqttInboundHandler h = handler(false);
        h.bindIdentity(connect("device-00001"));

        // 关闭鉴权时保持原有行为，不引入破坏性变更
        assertTrue(h.canPublish("device/device-00002/telemetry"));
        assertTrue(h.canSubscribe("device/device-00002/command"));
    }
}
