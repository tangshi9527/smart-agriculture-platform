package com.smartagri.mq.consumer;

import com.smartagri.ai.AiProperties;
import com.smartagri.ai.AlertExplainer;
import com.smartagri.ai.ExplainRequest;
import com.smartagri.alert.AlertEngine;
import com.smartagri.alert.AlertEvent;
import com.smartagri.mq.TelemetryConsumer;
import com.smartagri.mq.TelemetryEvent;
import com.smartagri.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 告警消费者：评估遥测事件并触发告警，触发的告警通过 WebSocket 实时推送给大屏。
 * 独立于持久化与遥测推送链路，告警风暴时不会拖垮其他处理。
 *
 * <p>AI 解读在这里挂载：只做「投递」，真正的模型调用在 {@code llmExecutor} 里跑，
 * 并有节流与线程池上限保护，所以即使大模型很慢也不会反压本条链路的告警判定。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertTelemetryConsumer implements TelemetryConsumer {

    private final AlertEngine alertEngine;
    private final WebSocketPushService push;
    private final AlertExplainer explainer;
    private final AiProperties aiProperties;

    @Override
    public String name() {
        return "alert";
    }

    @Override
    public void consume(TelemetryEvent event) {
        try {
            List<AlertEvent> fired = alertEngine.evaluate(event.toTelemetry());
            for (AlertEvent e : fired) {
                push.pushAlert(e);
                maybeExplain(e);
            }
        } catch (Exception e) {
            log.error("告警评估失败 device={}: {}", event.getDeviceId(), e.getMessage());
            // 不再静默吞掉：告警丢一条就是漏报一次，交给上层（总线/监听器）决定重试或进死信。
            // LocalBus 的 consumeSafely 会再记一次并计数；Rabbit 监听器会重试一次后再记。
            throw new IllegalStateException("告警链路处理失败 device=" + event.getDeviceId(), e);
        }
    }

    /** 达到级别门槛才自动解读（默认只解读 CRITICAL，省 token） */
    private void maybeExplain(AlertEvent e) {
        if (!aiProperties.getExplain().isAuto()) {
            return;
        }
        if (!AlertExplainer.severityAtLeast(e.getSeverity(), aiProperties.getExplain().getMinSeverity())) {
            return;
        }
        explainer.submit(ExplainRequest.builder()
                .cid(e.getId())
                .deviceId(e.getDeviceId())
                .ruleName(e.getRuleName())
                .metric(e.getMetric())
                .value(e.getValue())
                .threshold(e.getThreshold())
                .operator(e.getOperator())
                .severity(e.getSeverity())
                .message(e.getMessage())
                .ts(e.getTs())
                .build(), true);
    }
}
