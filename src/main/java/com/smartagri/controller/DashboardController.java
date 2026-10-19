package com.smartagri.controller;

import com.smartagri.alert.AlertService;
import com.smartagri.device.DeviceShadowService;
import com.smartagri.mqtt.session.DeviceSessionRegistry;
import com.smartagri.common.Result;
import com.smartagri.websocket.WebSocketPushService;
import com.smartagri.gateway.ApiVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大屏总览接口：聚合状态、连接数、最近告警与告警规则，便于首屏加载。
 */
@RestController
@RequestMapping("/api/dashboard")
@ApiVersion(1)
public class DashboardController {

    private final DeviceShadowService shadow;
    private final AlertService alertService;
    private final DeviceSessionRegistry registry;
    private final WebSocketPushService push;

    public DashboardController(DeviceShadowService shadow, AlertService alertService,
                              DeviceSessionRegistry registry, WebSocketPushService push) {
        this.shadow = shadow;
        this.alertService = alertService;
        this.registry = registry;
        this.push = push;
    }

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", shadow.summary());
        m.put("connections", registry.connectionCount());
        m.put("devices", shadow.onlineCount());
        m.put("dashboards", push.onlineDashboards());
        m.put("recentAlerts", alertService.recent().stream().limit(20).toList());
        m.put("activeRules", alertService.listRules());
        return Result.ok(m);
    }
}
