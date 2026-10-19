package com.smartagri.controller;

import com.smartagri.common.Result;
import com.smartagri.gateway.ApiVersion;
import com.smartagri.gateway.ratelimit.KeyType;
import com.smartagri.gateway.ratelimit.RateLimit;
import com.smartagri.influx.InfluxService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 时序数据查询接口：原始库 / 降采样库 / 长周期聚合，并统计查询耗时。
 */
@RestController
@RequestMapping("/api/telemetry")
@ApiVersion(1)
@RateLimit(capacity = 120, refillTokens = 120, refillSeconds = 60, keyType = KeyType.USER)
public class TelemetryController {

    private final InfluxService influx;

    public TelemetryController(InfluxService influx) {
        this.influx = influx;
    }

    @GetMapping("/query")
    @CircuitBreaker(name = "telemetry", fallbackMethod = "telemetryFallback")
    public Result<Map<String, Object>> query(
            @RequestParam String deviceId,
            @RequestParam String metric,
            @RequestParam(defaultValue = "-1h") String start,
            @RequestParam(defaultValue = "now()") String stop) {
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> data = influx.queryRaw(deviceId, metric, start, stop);
        long cost = System.currentTimeMillis() - t0;
        return Result.ok(wrap(data, cost));
    }

    @GetMapping("/downsampled")
    @CircuitBreaker(name = "telemetry", fallbackMethod = "telemetryFallback")
    public Result<Map<String, Object>> downsampled(
            @RequestParam String deviceId,
            @RequestParam String metric,
            @RequestParam(defaultValue = "-24h") String start,
            @RequestParam(defaultValue = "now()") String stop) {
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> data = influx.queryDownsampled(deviceId, metric, start, stop);
        long cost = System.currentTimeMillis() - t0;
        return Result.ok(wrap(data, cost));
    }

    @GetMapping("/agg")
    @CircuitBreaker(name = "telemetry", fallbackMethod = "telemetryFallback")
    public Result<Map<String, Object>> agg(
            @RequestParam String deviceId,
            @RequestParam String metric,
            @RequestParam(defaultValue = "-7d") String start,
            @RequestParam(defaultValue = "now()") String stop) {
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> data = influx.queryLongPeriodAgg(deviceId, metric, start, stop);
        long cost = System.currentTimeMillis() - t0;
        return Result.ok(wrap(data, cost));
    }

    /**
     * 长周期聚合耗时对比：原始库全量扫描 vs 降采样库聚合。
     *
     * <p>只负责把两条路径各自的耗时算出来返回，不做任何提速断言——
     * 差距大小取决于数据量与查询窗口，需要真实 InfluxDB 灌数据才有意义。
     * 本项目开发机上没条件起 InfluxDB，所以这里没有基准数字。</p>
     */
    @GetMapping("/benchmark")
    @CircuitBreaker(name = "telemetry", fallbackMethod = "telemetryFallback")
    public Result<Map<String, Object>> benchmark(
            @RequestParam String deviceId,
            @RequestParam String metric,
            @RequestParam(defaultValue = "-7d") String start,
            @RequestParam(defaultValue = "now()") String stop) {
        Map<String, Object> result = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        int rawCount = influx.queryRaw(deviceId, metric, start, stop).size();
        long rawCost = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        int dsCount = influx.queryDownsampled(deviceId, metric, start, stop).size();
        long dsCost = System.currentTimeMillis() - t1;

        result.put("rawCostMs", rawCost);
        result.put("rawPoints", rawCount);
        result.put("downsampledCostMs", dsCost);
        result.put("downsampledPoints", dsCount);
        return Result.ok(result);
    }

    private Map<String, Object> wrap(List<Map<String, Object>> data, long cost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("costMs", cost);
        m.put("count", data.size());
        m.put("data", data);
        return m;
    }

    /**
     * 时序查询熔断降级：当 InfluxDB 下游连续失败触发熔断时返回降级响应，
     * 避免雪崩并把错误收敛为标准 Result（code=503），便于前端提示。
     */
    private Result<Map<String, Object>> telemetryFallback(String deviceId, String metric,
                                                          String start, String stop, Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("degraded", true);
        m.put("reason", "时序查询下游熔断/降级：" + (e == null ? "unknown" : e.getMessage()));
        return Result.fail(503, "时序查询暂不可用（已熔断降级）");
    }
}
