package com.smartagri.gateway;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 Controller 所属 API 主版本。
 *
 * <p>配合 {@link ApiVersionRequestMappingHandlerMapping} 使用：被标记的 Controller 会在
 * {@code /api/v{N}/**} 下注册版本化路由，同时（默认）在原 {@code /api/**} 路径保留旧版本，
 * 实现「版本兼容」——新增 v2 时不破坏既有 v1 调用方。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiVersion {
    /** 主版本号，例如 1 表示 /api/v1 */
    int value() default 1;
}
