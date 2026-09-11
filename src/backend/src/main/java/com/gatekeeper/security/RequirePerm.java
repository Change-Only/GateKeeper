package com.gatekeeper.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限点校验注解 — T02 权限基座核心注解
 *
 * <p>使用方法：标注在 Controller 方法上（或类上），value 是权限点编码（与原型 menus.permCode 一一对应），
 * 例如 {@code @RequirePerm("app:credential:reset")}。</p>
 *
 * <p>校验时机：在 JwtAuthInterceptor 完成登录态认证后，由 PermissionInterceptor 进行权限点校验。
 * 如果方法没有该注解，则该方法不进行权限点拦截（兼容存量未加固接口）。</p>
 *
 * <p>风险标识：risk 用于 OperationLogAspect 判断是否高危操作（高危强制写审计，
 * risk_flag=1），与 {@link com.gatekeeper.entity.SysMenu#riskFlag} 一致。</p>
 *
 * @author GateKeeper
 * @since T02 (APIM V2)
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /**
     * 权限点编码，例如 {@code "app:credential:reset"}、{@code "app:disable"}。
     *
     * <p>取值约定：与原型 menus 表 permCode 完全一致。</p>
     */
    String value();

    /**
     * 是否高危操作：高危操作必须强制写审计日志 riskFlag=1。
     *
     * @return true=高危, false=普通（默认）
     */
    boolean risk() default false;
}
