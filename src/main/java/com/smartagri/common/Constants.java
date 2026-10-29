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
    /**
     * Redis 在线设备集合：<b>ZSET</b>（member=deviceId，score=最后心跳的毫秒时间戳）。
     *
     * <p>不是普通 SET。名字里带 ZSET 是为了让下一个读代码的人一眼知道
     * "超时设备靠 ZRANGEBYSCORE 一次取回"，不必先去翻实现。</p>
     */
    public static final String ONLINE_ZSET = "agri:online";
    /**
     * 出现过的全部设备（SET，只增不删）。
     *
     * <p>存在的唯一理由：回答「一共多少台设备」要能 O(1) 答。
     * 上一版是遍历所有 {@code agri:shadow:*} 的 key 再数一遍，
     * 而那个动作每次有人打开大屏都会做一次。</p>
     */
    public static final String DEVICES_SET = "agri:devices";
    /** Redis 告警规则缓存 key */
    public static final String RULE_CACHE = "agri:rules";
}
