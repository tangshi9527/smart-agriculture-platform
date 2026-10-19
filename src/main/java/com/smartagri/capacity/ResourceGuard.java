package com.smartagri.capacity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本机资源守卫：为「降级 / 节流」两套机制提供统一的资源读数。
 *
 * <p><b>为什么需要它：</b>这个项目跑在开发机上（IDE、浏览器、录屏同时开着），
 * 很多"降级"的真实原因不是外部依赖挂了，而是<b>本机 CPU 顶不住</b>。
 * 这两者的正确反应完全不同：</p>
 *
 * <table>
 *   <tr><th>触发源</th><th>正确反应</th><th>代价</th></tr>
 *   <tr><td>外部依赖不可用（ping 不通 / 连接异常）</td><td>降级到内存</td><td>数据进不了时序库，需要补写</td></tr>
 *   <tr><td>本机资源紧张（CPU / 堆）</td><td><b>节流</b>：降低采样率、减小模拟器规模</td><td>数据变稀疏，但不丢</td></tr>
 *   <tr><td>消费队列积压</td><td><b>背压</b>：有界队列 + 丢弃计数</td><td>丢单点</td></tr>
 * </table>
 *
 * <p>把三者混成"降级 → 内存"一条路，就会出现「CPU 高 → 切内存 → ping 通 → 切回 → CPU 又高」的
 * 每次 30 秒一次的尖峰，比一直降级更折腾。所以这里只负责"测"，判断交给调用方。</p>
 */
@Slf4j
@Component
public class ResourceGuard {

    /** 演示态建议 0.5（IDE + 浏览器常驻），生产态 0.8 */
    @Value("${capacity.cpu-threshold:0.7}")
    private double cpuThreshold;

    @Value("${capacity.heap-threshold:0.8}")
    private double heapThreshold;

    private final com.sun.management.OperatingSystemMXBean osBean;

    /** 进程 CPU 负载 0~1；-1 表示当前平台取不到 */
    private volatile double cpuLoad = -1;
    /** 堆占用比 0~1 */
    private volatile double heapUsed = 0;
    /** 上次采样时间 */
    private volatile long sampledAt = 0;

    public ResourceGuard() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        this.osBean = (bean instanceof com.sun.management.OperatingSystemMXBean sun) ? sun : null;
        if (this.osBean == null) {
            log.warn("当前 JVM 不提供 CPU 负载读数，资源门槛将只依据堆占用");
        }
    }

    @Scheduled(fixedDelayString = "${capacity.sample-ms:5000}")
    public void sample() {
        try {
            if (osBean != null) {
                double v = osBean.getCpuLoad();          // JDK 14+，0~1，不可用时为负
                if (v >= 0) {
                    cpuLoad = v;
                }
            }
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            if (max > 0) {
                heapUsed = (double) (rt.totalMemory() - rt.freeMemory()) / max;
            }
            sampledAt = System.currentTimeMillis();
        } catch (Exception e) {
            log.debug("资源采样失败: {}", e.getMessage());
        }
    }

    /**
     * 资源是否紧张。
     * CPU 读数不可用（-1）时只看堆，避免因为拿不到指标就永远"不紧张"。
     */
    public boolean busy() {
        if (cpuLoad >= 0 && cpuLoad > cpuThreshold) {
            return true;
        }
        return heapUsed > heapThreshold;
    }

    /**
     * 是否允许「恢复」——比 {@link #busy()} 更严，形成滞回带。
     *
     * <p>恢复用 HIGH 的上限（0.7）会抖：CPU 在 0.69 / 0.71 之间震荡时，会反复「恢复→写入→又降级」。
     * 所以恢复要求落到 0.8×阈值（默认 0.56）以下，与触发线之间留一段缓冲。
     * 代价是恢复略慢一点，换来的是不再跳来跳去。</p>
     */
    public boolean canRecover() {
        if (cpuLoad >= 0 && cpuLoad > cpuThreshold * 0.8) {
            return false;
        }
        return heapUsed <= heapThreshold * 0.9;
    }

    /** LOW / MEDIUM / HIGH，供 health 与前端展示 */
    public String level() {
        if (busy()) {
            return "HIGH";
        }
        if (cpuLoad >= 0 && cpuLoad > cpuThreshold * 0.8) {
            return "MEDIUM";
        }
        if (heapUsed > heapThreshold * 0.9) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /**
     * 按当前压力给出「建议上报间隔」——这是节流信号，不是降级信号。
     *
     * @param baseIntervalMs 模拟器/设备的基准上报间隔
     * @return 建议间隔（只增不减，上限 10s）
     */
    public int suggestedReportIntervalMs(int baseIntervalMs) {
        int base = Math.max(500, baseIntervalMs);
        return switch (level()) {
            case "HIGH" -> Math.min(base * 5, 10_000);
            case "MEDIUM" -> Math.min(base * 2, 10_000);
            default -> base;
        };
    }

    /** 给 health / 前端的一次性快照 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level());
        m.put("cpuLoad", cpuLoad < 0 ? null : Math.round(cpuLoad * 1000) / 1000.0);
        m.put("heapUsed", Math.round(heapUsed * 1000) / 1000.0);
        m.put("cpuThreshold", cpuThreshold);
        m.put("heapThreshold", heapThreshold);
        m.put("sampledAt", sampledAt);
        m.put("suggestedReportIntervalMs", suggestedReportIntervalMs(2000));
        m.put("advice", advice());
        return m;
    }

    private String advice() {
        return switch (level()) {
            case "HIGH" -> "本机资源紧张：建议把模拟器上报间隔调到 5~10s，或减少设备数。切内存降级解决不了 CPU 问题。";
            case "MEDIUM" -> "资源偏高：可把上报间隔调到 4~5s，观察队列是否积压。";
            default -> "资源正常，可维持当前采样率。";
        };
    }

    public double cpuLoad() {
        return cpuLoad;
    }

    public double heapUsed() {
        return heapUsed;
    }
}
