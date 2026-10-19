package com.smartagri.mq.consumer;

import com.smartagri.mq.TelemetryConsumer;
import com.smartagri.mq.TelemetryEvent;
import com.smartagri.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 实时推送消费者：将遥测事件广播给所有在线大屏。
 * 与持久化 / 告警链路解耦，大屏端的高吞吐广播压力不会反压到设备接入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushTelemetryConsumer implements TelemetryConsumer {

    private final WebSocketPushService push;

    @Override
    public String name() {
        return "push";
    }

    @Override
    public void consume(TelemetryEvent event) {
        try {
            push.pushTelemetry(event.toTelemetry());
        } catch (Exception e) {
            log.error("遥测推送失败 device={}: {}", event.getDeviceId(), e.getMessage());
        }
    }
}
