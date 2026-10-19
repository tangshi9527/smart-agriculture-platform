package com.smartagri.websocket;

import com.smartagri.alert.AlertEvent;
import com.smartagri.command.DeviceCommand;
import com.smartagri.common.JsonUtil;
import com.smartagri.device.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实时推送服务：将遥测、告警、设备状态变化封装为消息广播给所有大屏。
 * 同时统计推送吞吐（遥测条/秒），供模拟器控制台展示压力指标。
 */
@Service
public class WebSocketPushService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketPushService.class);

    private final DashboardWebSocketHandler handler;

    private final AtomicLong telemetryTotal = new AtomicLong();
    private final AtomicLong alertTotal = new AtomicLong();
    private final AtomicInteger secTelemetry = new AtomicInteger();
    private final AtomicInteger lastSecondTelemetry = new AtomicInteger();

    public WebSocketPushService(DashboardWebSocketHandler handler) {
        this.handler = handler;
    }

    public void pushTelemetry(Telemetry t) {
        // 吞吐计数始终保留（供大屏/控制台展示压力指标）
        telemetryTotal.incrementAndGet();
        secTelemetry.incrementAndGet();
        // 仅在确有订阅大屏时序列化并广播，避免无界面时每条遥测空转占用 CPU
        if (handler.onlineCount() <= 0) {
            return;
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "telemetry");
        msg.put("deviceId", t.getDeviceId());
        msg.put("ts", t.getTs());
        msg.put("metrics", t.getMetrics());
        handler.broadcast(JsonUtil.toJson(msg));
    }

    public void pushAlert(AlertEvent e) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "alert");
        msg.put("data", e);
        handler.broadcast(JsonUtil.toJson(msg));
        alertTotal.incrementAndGet();
    }

    public void pushStatus(Map<String, Object> status) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "status");
        msg.put("data", status);
        handler.broadcast(JsonUtil.toJson(msg));
    }

    /** 控制指令状态变更广播（下发/执行/超时），供前端指令历史实时刷新 */
    public void pushCommand(DeviceCommand cmd) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "command");
        msg.put("data", cmd);
        handler.broadcast(JsonUtil.toJson(msg));
    }

    /**
     * AI 告警解读的流式推送。
     *
     * @param phase start / chunk / done
     * @param data  载荷（含 cid，前端据此把增量拼到对应告警上）
     */
    public void pushAiExplain(String phase, Map<String, Object> data) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "ai_explain_" + phase);
        msg.put("data", data);
        handler.broadcast(JsonUtil.toJson(msg));
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        lastSecondTelemetry.set(secTelemetry.getAndSet(0));
    }

    public int telemetryPerSecond() {
        return lastSecondTelemetry.get();
    }

    public long telemetryTotal() {
        return telemetryTotal.get();
    }

    public long alertTotal() {
        return alertTotal.get();
    }

    public int onlineDashboards() {
        return handler.onlineCount();
    }
}
