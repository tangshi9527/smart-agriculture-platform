package com.smartagri.alert;

import com.smartagri.device.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动态告警引擎：
 * - 规则缓存在内存，数据库变更或定时刷新后即时生效（无需重启）
 * - 对遥测做阈值判定，对已激活规则去重避免刷屏
 * - 维护最近告警与当前活动告警，供大屏订阅
 */
@Service
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);
    private static final int MAX_RECENT = 1000;

    private final AlertRuleRepository ruleRepository;
    private final AlertRecordRepository recordRepository;
    private volatile List<AlertRule> rules = new ArrayList<>();
    private final Map<String, AlertEvent> active = new ConcurrentHashMap<>();
    /** deviceId|ruleId -> 条件首次持续命中时刻(ms)，用于「持续时长」判定 */
    private final Map<String, Long> firstTrueTs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<AlertEvent> recent = new ConcurrentLinkedDeque<>();
    private final AtomicLong seq = new AtomicLong();

    public AlertEngine(AlertRuleRepository ruleRepository, AlertRecordRepository recordRepository) {
        this.ruleRepository = ruleRepository;
        this.recordRepository = recordRepository;
    }

    @PostConstruct
    public void loadRules() {
        reloadRules();
    }

    /** 热更新：规则变更或每 5s 兜底刷新 */
    public synchronized void reloadRules() {
        this.rules = new ArrayList<>(ruleRepository.findAll());
        // 仅清理已删除规则的持续时长计时，保留现存规则进行中的计时（否则每 5s 兜底刷新会把计时清零，导致时长型规则永远触发不了）
        java.util.Set<String> ids = this.rules.stream()
                .map(r -> String.valueOf(r.getId()))
                .collect(java.util.stream.Collectors.toSet());
        firstTrueTs.keySet().removeIf(k -> {
            int idx = k.lastIndexOf('|');
            return idx >= 0 && !ids.contains(k.substring(idx + 1));
        });
        log.info("告警规则已加载/刷新，当前启用 {} 条", this.rules.size());
    }

    @Scheduled(fixedDelay = 5000)
    public void scheduledReload() {
        reloadRules();
    }

    public List<AlertRule> getRules() {
        return rules;
    }

    /** 评估一条遥测，返回本次新触发的告警（支持多条件组合与持续时长）。 */
    public List<AlertEvent> evaluate(Telemetry t) {
        List<AlertEvent> fired = new ArrayList<>();
        for (AlertRule r : rules) {
            if (!r.isEnabled()) {
                continue;
            }
            if (r.getDeviceId() != null && !r.getDeviceId().isEmpty()
                    && !r.getDeviceId().equals(t.getDeviceId())) {
                continue;
            }
            List<AlertCondition> conds = r.effectiveConditions();
            if (conds.isEmpty()) {
                continue;
            }
            boolean met = evaluateConditions(conds, r.getLogic(), t);
            String key = t.getDeviceId() + "|" + r.getId();
            if (met) {
                // —— 持续时长判定 ——
                if (r.getDurationSec() > 0) {
                    long now = System.currentTimeMillis();
                    Long first = firstTrueTs.get(key);
                    if (first == null) {
                        firstTrueTs.put(key, now); // 首次命中，开始计时
                        continue;
                    }
                    if (now - first < (long) r.getDurationSec() * 1000L) {
                        continue; // 持续中，尚未达到时长
                    }
                }
                if (active.containsKey(key)) {
                    continue; // 已激活，去重
                }
                AlertEvent e = buildEvent(r, t, conds);
                active.put(key, e);
                record(e);
                persist(e);
                fired.add(e);
            } else {
                // 条件恢复：只有「之前确实处于激活态」才算一次消解，避免每条正常遥测都去 update 数据库
                AlertEvent wasActive = active.remove(key);
                firstTrueTs.remove(key);
                if (wasActive != null) {
                    resolveRecord(wasActive);
                }
            }
        }
        return fired;
    }

    /**
     * 把已经恢复正常的告警记录置为 RESOLVED。
     *
     * <p>之前只有 {@code ALERT_RECORD.status} 里写着 RESOLVED 这个可能值，但没有任何代码会写入它——
     * 结果是设备早就恢复正常了，告警中心还挂着一条 ACTIVE，只能靠人工点"确认"。
     * 这既让告警列表长期失真，也让"当前活跃告警数"这个指标永远只增不减。</p>
     */
    private void resolveRecord(AlertEvent e) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                int n = recordRepository.resolveActive(e.getDeviceId(), e.getRuleId());
                if (n > 0) {
                    log.info("告警已自动消解 device={} rule={} 受影响记录 {} 条", e.getDeviceId(), e.getRuleId(), n);
                }
            } catch (Exception ex) {
                log.warn("告警自动消解失败 device={} rule={}: {}", e.getDeviceId(), e.getRuleId(), ex.getMessage());
            }
        });
    }

    /** 按 logic(AND/OR) 组合多条条件求值。AND：全部命中才 true；OR：任一命中即 true。 */
    private boolean evaluateConditions(List<AlertCondition> conds, String logic, Telemetry t) {
        boolean or = "OR".equalsIgnoreCase(logic);
        for (AlertCondition c : conds) {
            Double v = t.getMetrics().get(c.getMetric());
            if (v == null) {
                if (!or) {
                    return false; // AND 模式下缺该指标即整体不命中
                }
                continue; // OR 模式下该条件不参与
            }
            boolean cv = compare(v, c.getOperator(), c.getThreshold());
            if (or) {
                if (cv) {
                    return true;
                }
            } else if (!cv) {
                return false;
            }
        }
        return !or; // AND 全部通过 -> true；OR 无一通过 -> false
    }

    /** 构造告警事件，并生成可读的多条件文案（代表条件取首个条件）。 */
    private AlertEvent buildEvent(AlertRule r, Telemetry t, List<AlertCondition> conds) {
        AlertCondition rep = conds.get(0);
        Double repVal = t.getMetrics().get(rep.getMetric());
        String sep = "OR".equalsIgnoreCase(r.getLogic()) ? " 或 " : " 且 ";
        String condText = conds.stream()
                .map(c -> {
                    Double v = t.getMetrics().get(c.getMetric());
                    return String.format("%s%s%.2f(实测%.2f)", c.getMetric(),
                            opSymbol(c.getOperator()), c.getThreshold(), v == null ? 0d : v);
                })
                .collect(java.util.stream.Collectors.joining(sep));
        String msg = r.getMessage() != null ? r.getMessage()
                : (t.getDeviceId() + " " + condText
                + (r.getDurationSec() > 0 ? String.format(" 持续%d秒", r.getDurationSec()) : ""));
        return AlertEvent.builder()
                .id("A" + seq.incrementAndGet())
                .ruleId(String.valueOf(r.getId()))
                .ruleName(r.getName())
                .deviceId(t.getDeviceId())
                .metric(rep.getMetric())
                .value(repVal == null ? 0d : repVal)
                .threshold(rep.getThreshold())
                .operator(rep.getOperator())
                .severity(r.getSeverity())
                .message(msg)
                .ts(System.currentTimeMillis())
                .status("ACTIVE")
                .build();
    }

    private static String opSymbol(String op) {
        return switch (op) {
            case "GT" -> ">";
            case "LT" -> "<";
            case "GTE" -> "≥";
            case "LTE" -> "≤";
            case "EQ" -> "=";
            default -> op == null ? "" : op;
        };
    }

    private boolean compare(double v, String op, double th) {
        return switch (op) {
            case "GT" -> v > th;
            case "LT" -> v < th;
            case "GTE" -> v >= th;
            case "LTE" -> v <= th;
            case "EQ" -> Math.abs(v - th) < 1e-9;
            default -> false;
        };
    }

    private void record(AlertEvent e) {
        recent.addFirst(e);
        while (recent.size() > MAX_RECENT) {
            recent.pollLast();
        }
    }

    /** 异步落库告警记录（避免阻塞接入 IO 线程） */
    private void persist(AlertEvent e) {
        AlertRecord rec = AlertRecord.builder()
                .ruleId(e.getRuleId())
                .ruleName(e.getRuleName())
                .deviceId(e.getDeviceId())
                .metric(e.getMetric())
                .value(e.getValue())
                .threshold(e.getThreshold())
                .operator(e.getOperator())
                .severity(e.getSeverity())
                .message(e.getMessage())
                .ts(e.getTs())
                .status("ACTIVE")
                .build();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                recordRepository.save(rec);
            } catch (Exception ex) {
                log.warn("告警落库失败（不影响实时推送）: {}", ex.getMessage());
            }
        });
    }

    public List<AlertEvent> getRecent() {
        return new ArrayList<>(recent);
    }

    public List<AlertEvent> getActive() {
        return new ArrayList<>(active.values());
    }
}
