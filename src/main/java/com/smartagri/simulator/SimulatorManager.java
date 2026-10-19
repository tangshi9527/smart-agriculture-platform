package com.smartagri.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模拟器管理器：优先以独立 Python 脚本（paho-mqtt 外部客户端）拉起设备模拟，
 * 该脚本行为完全等同真实硬件；若环境中无 Python 或启动失败，则回退到内置 Java(Netty) 模拟器。
 * 通过 ProcessBuilder 托管子进程，提供启停与状态查询。
 */
@Service
public class SimulatorManager {

    private static final Logger log = LoggerFactory.getLogger(SimulatorManager.class);

    @Value("${simulator.python:python}")
    private String python;
    @Value("${simulator.script:simulator/device_sim.py}")
    private String script;
    @Value("${simulator.broker-host:127.0.0.1}")
    private String brokerHost;
    @Value("${simulator.broker-port:1883}")
    private int brokerPort;
    @Value("${mqtt.auth.simulator-username:simulator}")
    private String simUsername;
    @Value("${mqtt.auth.simulator-secret:sim-secret-2026-change-me}")
    private String simSecret;

    private final DeviceSimulator embedded;

    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private volatile SimulatorConfig lastConfig;
    private volatile long startedAt = 0;

    public SimulatorManager(DeviceSimulator embedded) {
        this.embedded = embedded;
    }

    public synchronized Map<String, Object> start(SimulatorConfig cfg) {
        stop();
        if (cfg.getBrokerHost() == null || cfg.getBrokerHost().isBlank()) {
            cfg.setBrokerHost(brokerHost);
        }
        if (cfg.getBrokerPort() == null) {
            cfg.setBrokerPort(brokerPort);
        }
        lastConfig = cfg;
        try {
            String scriptPath = resolveScript();
            List<String> cmd = new ArrayList<>();
            cmd.add(python);
            cmd.add(scriptPath);
            cmd.add("--host");        cmd.add(cfg.getBrokerHost());
            cmd.add("--port");        cmd.add(String.valueOf(cfg.getBrokerPort()));
            cmd.add("--devices");     cmd.add(String.valueOf(cfg.getDevices()));
            cmd.add("--connections"); cmd.add(String.valueOf(cfg.getConnections()));
            cmd.add("--interval");    cmd.add(String.valueOf(cfg.getReportIntervalMs()));
            cmd.add("--heartbeat");   cmd.add(String.valueOf(cfg.getHeartbeatIntervalMs()));
            cmd.add("--fault-rate");  cmd.add(String.valueOf(cfg.getFaultRate()));
            cmd.add("--base-temp");   cmd.add(String.valueOf(cfg.getBaseTemp()));
            cmd.add("--temp-amplitude"); cmd.add(String.valueOf(cfg.getTempAmplitude()));
            cmd.add("--username");    cmd.add(simUsername);
            cmd.add("--password");    cmd.add(simSecret);
            if (cfg.getDurationSec() > 0) {
                cmd.add("--duration"); cmd.add(String.valueOf(cfg.getDurationSec()));
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            File logFile = new File("simulator/sim.log");
            logFile.getParentFile().mkdirs();
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            processRef.set(p);
            startedAt = System.currentTimeMillis();
            log.info("已启动 Python 设备模拟器: {} 设备 / {} 连接", cfg.getDevices(), cfg.getConnections());
            return status();
        } catch (Exception e) {
            log.warn("Python 模拟器启动失败({})，回退到内置 Java 模拟器", e.getMessage());
            try {
                embedded.start(cfg.getDevices(), cfg.getConnections());
            } catch (Exception ex) {
                log.error("内置 Java 模拟器亦启动失败: {}", ex.getMessage());
            }
            startedAt = System.currentTimeMillis();
            return status();
        }
    }

    private String resolveScript() {
        File f = new File(script);
        return f.exists() ? f.getAbsolutePath() : script;
    }

    public synchronized void stop() {
        Process p = processRef.getAndSet(null);
        if (p != null && p.isAlive()) {
            p.destroy();
            log.info("已停止 Python 模拟器子进程");
        }
        if (embedded != null) {
            embedded.stop();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        Process p = processRef.get();
        boolean pythonAlive = p != null && p.isAlive();
        boolean embeddedRunning = embedded != null && embedded.isRunning();
        m.put("running", pythonAlive || embeddedRunning);
        m.put("engine", pythonAlive ? "python" : (embeddedRunning ? "embedded-java" : "none"));
        m.put("pid", pythonAlive ? p.pid() : null);
        m.put("startedAt", startedAt == 0 ? null : startedAt);
        m.put("uptimeSec", startedAt == 0 ? 0 : (System.currentTimeMillis() - startedAt) / 1000);
        m.put("config", lastConfig);
        return m;
    }

    @PreDestroy
    public void destroy() {
        stop();
    }
}
