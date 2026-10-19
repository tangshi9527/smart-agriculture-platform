package com.smartagri.controller;

import com.smartagri.common.Result;
import com.smartagri.simulator.SimulatorConfig;
import com.smartagri.simulator.SimulatorManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设备模拟器控制接口（/simulator 已在 SecurityConfig 白名单放行）。
 * 接收前端界面下发的 SimulatorConfig，由后端拉起 Python 脚本（或兜底 Java 模拟器）。
 */
@RestController
@RequestMapping("/simulator")
public class SimulatorController {

    private final SimulatorManager manager;

    public SimulatorController(SimulatorManager manager) {
        this.manager = manager;
    }

    @PostMapping("/start")
    public Result<Map<String, Object>> start(@RequestBody SimulatorConfig cfg) {
        return Result.ok(manager.start(cfg));
    }

    @PostMapping("/stop")
    public Result<Void> stop() {
        manager.stop();
        return Result.ok();
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(manager.status());
    }

    @GetMapping("/defaults")
    public Result<SimulatorConfig> defaults() {
        return Result.ok(SimulatorConfig.builder().build());
    }
}
