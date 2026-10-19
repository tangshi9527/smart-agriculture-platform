package com.smartagri.command;

import com.smartagri.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 设备控制指令接口（位于 JWT 白名单内，前端经 axios 拦截器带令牌访问）。
 */
@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final CommandService commandService;

    public CommandController(CommandService commandService) {
        this.commandService = commandService;
    }

    /**
     * 下发控制指令。
     * 请求体：{ deviceId, action, params? }
     * 返回：完整指令记录（含初始状态 PENDING/SENT/NO_SESSION）。
     */
    @PostMapping
    public Result<DeviceCommand> send(@RequestBody Map<String, Object> body) {
        String deviceId = body.get("deviceId") == null ? null : String.valueOf(body.get("deviceId"));
        String action = body.get("action") == null ? null : String.valueOf(body.get("action"));
        if (deviceId == null || deviceId.isBlank() || action == null || action.isBlank()) {
            return Result.fail("deviceId 与 action 均为必填");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map
                ? (Map<String, Object>) body.get("params") : null;
        return Result.ok(commandService.sendCommand(deviceId, action, params));
    }

    /** 指令历史（可按设备过滤，最新在前） */
    @GetMapping
    public Result<List<DeviceCommand>> list(@RequestParam(required = false) String deviceId,
                                           @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(commandService.recent(Math.max(1, Math.min(limit, 1000)), deviceId));
    }

    /** 单条指令详情 */
    @GetMapping("/{id}")
    public Result<DeviceCommand> get(@PathVariable("id") String id) {
        return Result.ok(commandService.get(id));
    }
}
