package com.smartagri;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class SmartAgriApplication {

    public static void main(String[] args) {
        if (needPortFallback(args)) {
            // 仅在「宿主注入了 SERVER__PORT、且用户没有显式指定端口」这一种情况下兜底。
            // 系统属性优先级高于环境变量，因此这里设成 8080 才能压掉注入值；
            // 但一旦无条件设置，就会把用户显式配置的 SERVER_PORT 也一并压掉（曾踩过这个坑）。
            System.setProperty("server.port", "8080");
        }
        SpringApplication.run(SmartAgriApplication.class, args);
    }

    /**
     * 判断是否需要为宿主环境兜底端口。
     *
     * <p>WorkBuddy/CodeBuddy 运行时会注入 {@code SERVER__PORT}（双下划线），Spring 的 relaxed binding
     * 会把它映射成 {@code server.port}，而该端口通常已被本进程占用，导致
     * "Port ... already in use"。此时才需要兜底。</p>
     *
     * <p>以下任一情况都<strong>不</strong>兜底，保证 12-factor 的「配置走环境变量」不被破坏：
     * 已用 {@code -Dserver.port} 指定、已设置 {@code SERVER_PORT}、命令行传了 {@code --server.port}。</p>
     */
    static boolean needPortFallback(String[] args) {
        if (System.getProperty("server.port") != null) {
            return false;
        }
        if (System.getenv("SERVER_PORT") != null) {
            return false;
        }
        if (args != null) {
            for (String a : args) {
                if (a != null && a.startsWith("--server.port")) {
                    return false;
                }
            }
        }
        return System.getenv("SERVER__PORT") != null;
    }
}
