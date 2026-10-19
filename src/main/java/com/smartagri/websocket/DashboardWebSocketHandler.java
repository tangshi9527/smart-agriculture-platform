package com.smartagri.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 大屏 WebSocket 处理器：维护会话集合，向所有在线大屏广播消息。
 */
@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DashboardWebSocketHandler.class);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        sessionLocks.put(session.getId(), new Object());
        log.info("大屏连接建立: {}，当前在线 {}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        sessionLocks.remove(session.getId());
        log.info("大屏连接关闭: {}，当前在线 {}", session.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 支持前端心跳 "ping" -> "pong"
        if ("ping".equalsIgnoreCase(message.getPayload())) {
            send(session, "pong");
        }
    }

    public void broadcast(String json) {
        for (Map.Entry<String, WebSocketSession> e : sessions.entrySet()) {
            send(e.getValue(), json);
        }
    }

    private void send(WebSocketSession session, String json) {
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            return;
        }
        Object lock = sessionLocks.get(session.getId());
        if (lock == null) {
            lock = sessionLocks.computeIfAbsent(session.getId(), k -> new Object());
        }
        try {
            synchronized (lock) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException ex) {
            sessions.remove(session.getId());
            sessionLocks.remove(session.getId());
        }
    }

    public int onlineCount() {
        return sessions.size();
    }
}
