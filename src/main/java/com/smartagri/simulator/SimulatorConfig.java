package com.smartagri.simulator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备模拟器运行参数（全部可由前端界面调整并下发）。
 *
 * <p>默认值刻意与 {@code simulator/device_sim.py} 的默认值保持一致（200 设备 / 200 连接）：
 * 一是避免「Python 缺失回退 Java 兜底」时负载突然放大数倍，二是首次演示不会把普通笔记本跑满风扇。
 * 需要更大规模时通过界面临时调大即可（前端上限 5000/10000），但本机调大会持续拉高 CPU。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimulatorConfig {

    @Builder.Default
    private int devices = 200;
    @Builder.Default
    private int connections = 200;
    /** 上报间隔(ms) */
    @Builder.Default
    private int reportIntervalMs = 2000;
    /** 心跳间隔(ms) */
    @Builder.Default
    private int heartbeatIntervalMs = 15000;
    /** 高温故障注入概率 0~1 */
    @Builder.Default
    private double faultRate = 0.04;
    /** 基准温度 */
    @Builder.Default
    private double baseTemp = 24.0;
    /** 温度波动幅度 */
    @Builder.Default
    private double tempAmplitude = 8.0;
    /** 运行时长(s)，0 表示不限时 */
    @Builder.Default
    private int durationSec = 0;

    private String brokerHost;
    private Integer brokerPort;
}
