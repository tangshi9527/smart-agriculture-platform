package com.smartagri.gateway.ratelimit;

/**
 * 限流维度：决定令牌桶 key 的计算方式。
 */
public enum KeyType {
    /** 按客户端 IP 限流（适合匿名/登录接口防刷）。 */
    IP,
    /** 按登录用户限流（需已认证，未登录时降级为 IP）。 */
    USER,
    /** 按接口限流（全局接口级限速）。 */
    API
}
