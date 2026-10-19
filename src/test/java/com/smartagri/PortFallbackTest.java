package com.smartagri;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 端口兜底逻辑：历史实现是无条件 {@code System.setProperty("server.port", "8080")}，
 * 而系统属性优先级高于环境变量，于是 {@code SERVER_PORT=8099} 会被静默忽略——
 * 12-factor 的「配置走环境变量」被破坏，也会让部署脚本的端口参数失效。
 */
class PortFallbackTest {

    @Test
    void explicitCommandLinePortWins() {
        assertFalse(SmartAgriApplication.needPortFallback(new String[]{"--server.port=8099"}));
        assertFalse(SmartAgriApplication.needPortFallback(
                new String[]{"--spring.profiles.active=mysql", "--server.port=9000"}));
    }

    @Test
    void systemPropertyPortWins() {
        System.setProperty("server.port", "8099");
        try {
            assertFalse(SmartAgriApplication.needPortFallback(new String[]{}));
        } finally {
            System.clearProperty("server.port");
        }
    }
}
