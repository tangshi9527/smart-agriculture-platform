package com.smartagri.controller;

import com.smartagri.alert.AlertEngine;
import com.smartagri.alert.AlertEvent;
import com.smartagri.alert.AlertRecord;
import com.smartagri.alert.AlertRule;
import com.smartagri.alert.AlertService;
import com.smartagri.alert.DslApplyResult;
import com.smartagri.alert.DslParseResult;
import com.smartagri.common.Result;
import com.smartagri.gateway.ApiVersion;
import com.smartagri.gateway.idempotent.Idempotent;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 告警规则与事件接口：规则变更即时热加载，无需重启；事件支持查询与确认。
 */
@RestController
@RequestMapping("/api/alerts")
@ApiVersion(1)
public class AlertController {

    private final AlertService alertService;
    private final AlertEngine alertEngine;

    public AlertController(AlertService alertService, AlertEngine alertEngine) {
        this.alertService = alertService;
        this.alertEngine = alertEngine;
    }

    @GetMapping("/rules")
    public Result<List<AlertRule>> listRules() {
        return Result.ok(alertService.listRules());
    }

    @PostMapping("/rules")
    @Idempotent(keyPrefix = "alert-rule")
    public Result<AlertRule> saveRule(@RequestBody AlertRule rule) {
        return Result.ok(alertService.saveRule(rule));
    }

    @DeleteMapping("/rules/{id}")
    public Result<Void> deleteRule(@PathVariable("id") Long id) {
        alertService.deleteRule(id);
        return Result.ok();
    }

    /** 解析 DSL（仅校验与预览，不写入）。请求体：{ "dsl": "..." } */
    @PostMapping("/rules/dsl/parse")
    public Result<DslParseResult> parseDsl(@RequestBody Map<String, String> body) {
        return Result.ok(alertService.parseDsl(body.get("dsl")));
    }

    /** 应用 DSL：解析无误则按规则名 upsert 写入并即时热加载。请求体：{ "dsl": "..." } */
    @PostMapping("/rules/dsl/apply")
    public Result<DslApplyResult> applyDsl(@RequestBody Map<String, String> body) {
        return Result.ok(alertService.applyDsl(body.get("dsl")));
    }

    @GetMapping
    public Result<List<AlertRecord>> list(@RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(alertService.listAlerts(status, limit));
    }

    @PostMapping("/{id}/ack")
    @Idempotent(keyPrefix = "alert-ack")
    public Result<Void> ack(@PathVariable("id") Long id) {
        alertService.ack(id);
        return Result.ok();
    }

    @PostMapping("/{id}/ignore")
    public Result<Void> ignore(@PathVariable("id") Long id) {
        alertService.ignore(id);
        return Result.ok();
    }

    @GetMapping("/recent")
    public Result<List<AlertEvent>> recent() {
        return Result.ok(alertService.recent());
    }

    @GetMapping("/active")
    public Result<List<AlertEvent>> active() {
        return Result.ok(alertService.active());
    }
}
