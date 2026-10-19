package com.smartagri.common;

/**
 * 全局常量。
 */
public final class Constants {
    private Constants() {}

    /** JWT 令牌前缀 */
    public static final String TOKEN_PREFIX = "Bearer ";
    /** 设备遥测 topic 模板 */
    public static final String TOPIC_TELEMETRY = "device/%s/telemetry";
    /** 设备上线 topic 模板 */
    public static final String TOPIC_ONLINE = "device/%s/online";
    /** 设备下线 topic 模板 */
    public static final String TOPIC_OFFLINE = "device/%s/offline";
    /** 云端下行控制指令 topic 模板 */
    public static final String TOPIC_COMMAND = "device/%s/command";
    /** 设备执行回执 topic 模板 */
    public static final String TOPIC_COMMAND_RESPONSE = "device/%s/command/response";
    /** Redis 设备影子 key 前缀 */
    public static final String SHADOW_PREFIX = "agri:shadow:";
    /** Redis 在线设备集合 key */
    public static final String ONLINE_SET = "agri:online";
    /** Redis 告警规则缓存 key */
    public static final String RULE_CACHE = "agri:rules";
}
