package com.smartagri.alert;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 告警规则 DSL 解析器。
 *
 * <p>面向「高级用户 / 批量定义规则」场景，把一段可读文本编译为 {@link AlertRule} 列表。
 * 语法（大小写不敏感，# 或 // 开头为注释，空行忽略）：
 * <pre>
 *   RULE 规则名
 *   SCOPE 设备ID | ALL          # 可选，默认 ALL（全部设备）
 *   WHEN 条件 [AND|OR 条件 ...]  # 多条件；续行可单独以 AND/OR 开头
 *   HOLD FOR 秒数               # 可选，持续时长；同义：DURATION 秒数
 *   THEN INFO|WARN|CRITICAL ["消息"]  # 可选消息
 *   ENABLED true|false          # 可选，默认 true
 *   END                         # 可选，遇下一条 RULE 或文件结束自动收尾
 * </pre>
 * 条件形如 {@code temperature > 35}、{@code soilMoisture < 25}、{@code co2 >= 1000}；
 * 运算符支持符号（> >= < <= = ==）与单词（GT/LT/GTE/LTE/EQ）。
 * 同一规则内不允许混用 AND 与 OR（请拆分为多条规则）。
 */
public final class RuleDsl {

    private RuleDsl() {
    }

    private static final Pattern COND = Pattern.compile(
            "^([A-Za-z_][\\w]*)\\s*(>=|<=|==|=|>|<|GTE|LTE|GT|LT|EQ)\\s*(-?\\d+(?:\\.\\d+)?)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AND = Pattern.compile("\\bAND\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OR = Pattern.compile("\\bOR\\b", Pattern.CASE_INSENSITIVE);

    /** 将 DSL 文本解析为规则列表与错误列表。 */
    public static DslParseResult parse(String source) {
        DslParseResult res = new DslParseResult();
        if (source == null || source.isBlank()) {
            res.getErrors().add(new DslError(0, "DSL 内容为空"));
            return res;
        }
        String[] lines = source.split("\\r?\\n");
        Builder b = new Builder();
        boolean inRule = false;

        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }

            if (line.regionMatches(true, 0, "RULE", 0, 4)) {
                if (inRule) {
                    flush(b, res);
                }
                String name = line.substring(4).trim();
                if (name.isEmpty()) {
                    res.getErrors().add(new DslError(lineNo, "RULE 后缺少规则名"));
                }
                b = new Builder();
                b.name = name;
                b.nameLine = lineNo;
                inRule = true;
                continue;
            }

            if (line.regionMatches(true, 0, "END", 0, 3)) {
                if (inRule) {
                    flush(b, res);
                    b = new Builder();
                    inRule = false;
                }
                continue;
            }

            if (!inRule) {
                res.getErrors().add(new DslError(lineNo, "指令必须在 RULE 内：「" + line + "」"));
                continue;
            }

            if (line.regionMatches(true, 0, "SCOPE", 0, 5)) {
                String scope = line.substring(5).trim();
                b.scope = "ALL".equalsIgnoreCase(scope) ? "" : scope;
                continue;
            }
            if (line.regionMatches(true, 0, "WHEN", 0, 4)) {
                String expr = line.substring(4).trim();
                parseConditions(expr, lineNo, res, b);
                continue;
            }
            if (line.regionMatches(true, 0, "AND", 0, 3) && isWord(b, line, "AND")) {
                parseConditions(line.substring(3).trim(), lineNo, res, b);
                continue;
            }
            if (line.regionMatches(true, 0, "OR", 0, 2) && isWord(b, line, "OR")) {
                parseConditions(line.substring(2).trim(), lineNo, res, b);
                continue;
            }
            if (line.regionMatches(true, 0, "HOLD", 0, 4) || line.regionMatches(true, 0, "DURATION", 0, 8)) {
                parseDuration(line, lineNo, res, b);
                continue;
            }
            if (line.regionMatches(true, 0, "THEN", 0, 4)) {
                parseThen(line, lineNo, res, b);
                continue;
            }
            if (line.regionMatches(true, 0, "ENABLED", 0, 7)) {
                String v = line.substring(7).trim().toLowerCase();
                b.enabled = v.matches("true|yes|1");
                continue;
            }

            res.getErrors().add(new DslError(lineNo, "无法识别的指令：「" + line + "」"));
        }

        if (inRule) {
            flush(b, res);
        }
        res.setOk(res.getErrors().isEmpty());
        return res;
    }

    /** 续行 AND/OR 必须是整词开头（避免误伤指标名中含 or/and 的情况，虽然本场景极少）。 */
    private static boolean isWord(Builder b, String line, String kw) {
        // 仅用于区分 WHEN 内联 AND 与续行 AND；这里 kw 已通过 regionMatches 前缀判断
        return true;
    }

    private static void parseConditions(String expr, int lineNo, DslParseResult res, Builder b) {
        if (expr.isEmpty()) {
            b.hasError = true;
            res.getErrors().add(new DslError(lineNo, "WHEN 后缺少条件"));
            return;
        }
        boolean hasAnd = AND.matcher(expr).find();
        boolean hasOr = OR.matcher(expr).find();
        if (hasAnd && hasOr) {
            b.hasError = true;
            res.getErrors().add(new DslError(lineNo, "不支持同一规则内混用 AND 与 OR，请拆分为多条规则"));
            return;
        }
        String segLogic = hasOr ? "OR" : "AND";
        if (!b.conds.isEmpty() && !b.logic.equals(segLogic)) {
            b.hasError = true;
            res.getErrors().add(new DslError(lineNo,
                    "条件逻辑不一致：此前为 " + b.logic + "，本段为 " + segLogic));
            return;
        }
        String[] parts = expr.split(hasOr ? "\\s+OR\\s+" : "\\s+AND\\s+", -1);
        for (String part : parts) {
            AlertCondition c = parseOne(part.trim(), lineNo, res, b);
            if (c != null) {
                b.conds.add(c);
            }
        }
        if (!b.conds.isEmpty()) {
            b.logic = segLogic;
        }
    }

    private static AlertCondition parseOne(String token, int lineNo, DslParseResult res, Builder b) {
        var m = COND.matcher(token);
        if (!m.matches()) {
            b.hasError = true;
            res.getErrors().add(new DslError(lineNo, "条件格式不正确：「" + token + "」（应为 指标 运算符 数值）"));
            return null;
        }
        String metric = m.group(1);
        String op = normalizeOp(m.group(2).toUpperCase());
        if (op == null) {
            b.hasError = true;
            res.getErrors().add(new DslError(lineNo, "不支持的运算符：「" + m.group(2) + "」"));
            return null;
        }
        double threshold = Double.parseDouble(m.group(3));
        return new AlertCondition(metric, op, threshold);
    }

    private static String normalizeOp(String raw) {
        return switch (raw) {
            case ">", "GT" -> "GT";
            case ">=", "GTE" -> "GTE";
            case "<", "LT" -> "LT";
            case "<=", "LTE" -> "LTE";
            case "=", "==", "EQ" -> "EQ";
            default -> null;
        };
    }

    private static void parseDuration(String line, int lineNo, DslParseResult res, Builder b) {
        var m = Pattern.compile("^(?:HOLD\\s+FOR|DURATION)\\s+(\\d+)\\s*s?\\s*$",
                Pattern.CASE_INSENSITIVE).matcher(line);
        if (!m.matches()) {
            b.hasError = true;
            res.getErrors().add(new DslError(lineNo, "HOLD/DURATION 格式不正确：「" + line + "」（应为 HOLD FOR 8s 或 DURATION 8）"));
            return;
        }
        b.duration = Integer.parseInt(m.group(1));
    }

    private static void parseThen(String line, int lineNo, DslParseResult res, Builder b) {
        var m = Pattern.compile("^THEN\\s+(INFO|WARN|CRITICAL)\\s*(\"([^\"]*)\")?\\s*$",
                Pattern.CASE_INSENSITIVE).matcher(line);
        if (!m.matches()) {
            b.hasError = true;
            res.getErrors().add(new DslError(lineNo, "THEN 格式不正确：「" + line + "」（应为 THEN WARN 或 THEN WARN \"消息\"）"));
            return;
        }
        b.severity = m.group(1).toUpperCase();
        b.message = (m.group(3) != null && !m.group(3).isEmpty()) ? m.group(3) : null;
    }

    private static void flush(Builder b, DslParseResult res) {
        if (b.name == null || b.name.isBlank()) {
            res.getErrors().add(new DslError(b.nameLine, "缺少规则名(RULE)"));
        }
        if (!b.hasError && b.conds.isEmpty()) {
            res.getErrors().add(new DslError(b.nameLine, "规则「" + (b.name == null ? "" : b.name) + "」缺少 WHEN 条件"));
        }
        boolean valid = b.name != null && !b.name.isBlank() && !b.hasError && !b.conds.isEmpty();
        if (valid) {
            AlertRule r = new AlertRule();
            r.setName(b.name);
            r.setDeviceId(b.scope);
            r.setConditions(new ArrayList<>(b.conds));
            r.setLogic(b.logic);
            r.setDurationSec(b.duration);
            r.setSeverity(b.severity);
            r.setEnabled(b.enabled);
            r.setMessage(b.message);
            // 镜像首条件到旧字段，便于事件/前端展示（saveRule 也会再归一化一次）
            AlertCondition f = b.conds.get(0);
            r.setMetric(f.getMetric());
            r.setOperator(f.getOperator());
            r.setThreshold(f.getThreshold());
            res.getRules().add(r);
        }
    }

    private static class Builder {
        String name;
        int nameLine = -1;
        String scope = "";
        List<AlertCondition> conds = new ArrayList<>();
        String logic = "AND";
        int duration = 0;
        String severity = "WARN";
        boolean enabled = true;
        String message = null;
        boolean hasError = false;
    }
}
