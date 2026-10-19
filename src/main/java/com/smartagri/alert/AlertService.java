package com.smartagri.alert;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartagri.alert.DslApplyResult;
import com.smartagri.alert.DslParseResult;
import com.smartagri.alert.RuleDsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 告警规则与事件管理：规则写库后即时刷新引擎缓存（热更新）；
 * 事件支持分页查询与确认(ACK)。
 */
@Service
public class AlertService {

    private final AlertRuleRepository ruleRepository;
    private final AlertEngine engine;
    private final AlertRecordRepository recordRepository;

    public AlertService(AlertRuleRepository ruleRepository, AlertEngine engine,
                       AlertRecordRepository recordRepository) {
        this.ruleRepository = ruleRepository;
        this.engine = engine;
        this.recordRepository = recordRepository;
    }

    @Transactional
    public AlertRule saveRule(AlertRule rule) {
        // 条件归一化：优先用 conditions 列表；若为空但旧单条件字段有值，则补一条
        List<AlertCondition> conds = rule.getConditions();
        if (conds == null) {
            conds = new ArrayList<>();
        }
        if (conds.isEmpty() && rule.getMetric() != null && !rule.getMetric().isEmpty()) {
            conds.add(new AlertCondition(rule.getMetric(), rule.getOperator(), rule.getThreshold()));
        }
        rule.setConditions(conds);
        // 把首条件镜像回旧字段，便于下游（事件/前端）展示
        if (!conds.isEmpty()) {
            AlertCondition first = conds.get(0);
            rule.setMetric(first.getMetric());
            rule.setOperator(first.getOperator());
            rule.setThreshold(first.getThreshold());
        }
        if (rule.getLogic() == null || rule.getLogic().isBlank()) {
            rule.setLogic("AND");
        }
        if (rule.getDurationSec() < 0) {
            rule.setDurationSec(0);
        }
        AlertRule saved = ruleRepository.save(rule);
        engine.reloadRules();
        return saved;
    }

    @Transactional
    public void deleteRule(Long id) {
        ruleRepository.deleteById(id);
        engine.reloadRules();
    }

    public List<AlertRule> listRules() {
        return engine.getRules();
    }

    /** 仅解析 DSL（不落库），用于前端「解析预览」与语法校验。 */
    public DslParseResult parseDsl(String dsl) {
        return RuleDsl.parse(dsl);
    }

    /**
     * 解析 DSL 并写入规则库（按规则名 upsert：同名则更新，否则新建）。
     * 全部解析成功才写入；存在语法错误时返回 ok=false 且不做任何写入。
     */
    @Transactional
    public DslApplyResult applyDsl(String dsl) {
        DslParseResult parsed = RuleDsl.parse(dsl);
        if (!parsed.isOk()) {
            return new DslApplyResult(false, 0, parsed.getRules(), parsed.getErrors());
        }
        Map<String, AlertRule> existing = ruleRepository.findAll().stream()
                .collect(Collectors.toMap(AlertRule::getName, r -> r, (a, b) -> a));
        int applied = 0;
        for (AlertRule r : parsed.getRules()) {
            AlertRule ex = existing.get(r.getName());
            if (ex != null) {
                r.setId(ex.getId()); // 同名更新，避免重复
            }
            saveRule(r); // 复用归一化 + 引擎热加载
            applied++;
        }
        return new DslApplyResult(true, applied, parsed.getRules(), parsed.getErrors());
    }

    public List<AlertEvent> recent() {
        return engine.getRecent();
    }

    public List<AlertEvent> active() {
        return engine.getActive();
    }

    /** 分页查询告警记录（可选按状态过滤） */
    public List<AlertRecord> listAlerts(String status, int limit) {
        List<AlertRecord> list = (status == null || status.isBlank())
                ? recordRepository.findAllByOrderByTsDesc()
                : recordRepository.findByStatusOrderByTsDesc(status);
        if (list.size() > limit) {
            list = list.subList(0, limit);
        }
        return list;
    }

    @Transactional
    public void ack(Long id) {
        recordRepository.findById(id).ifPresent(r -> {
            r.setStatus("ACKED");
            recordRepository.save(r);
        });
    }

    @Transactional
    public void ignore(Long id) {
        recordRepository.findById(id).ifPresent(r -> {
            r.setStatus("IGNORED");
            recordRepository.save(r);
        });
    }
}
