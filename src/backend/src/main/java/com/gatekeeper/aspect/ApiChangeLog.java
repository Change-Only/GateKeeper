package com.gatekeeper.aspect;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口变更留痕注解 — T03b 变更历史（api_change_log）自动写入切点
 *
 * <p>标注在「会修改接口资产」的 Controller 方法上，由
 * {@link ApiChangeLogAspect} 在方法<strong>成功返回后</strong>、且事务提交后
 * （afterCommit）自动追加一条 {@code api_change_log}，避免在每个方法里手写留痕代码。</p>
 *
 * <p>示例：{@code @ApiChangeLog(value = "修改接口参数", changeType = "UPDATE", fieldName = "params")}</p>
 *
 * <p>操作人解析：从请求属性 {@code X-USER-ID} / {@code X-USERNAME}（由 JwtAuthInterceptor 写入）；
 * 接口ID（apiId）解析：优先取方法入参/返回对象上的 {@code getApiId()}，其次取名为 {@code apiId} 的入参，
 * 再次取返回对象 {@code ApiInterface} 的主键。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiChangeLog {

    /**
     * 变更说明 / 原因，写入 {@code api_change_log.change_reason}。
     *
     * @return 变更说明（默认空）
     */
    String value() default "";

    /**
     * 变更类型，写入 {@code api_change_log.change_type}。
     *
     * <p>取值：CREATE / UPDATE / PUBLISH / DEPRECATE / OFFLINE / DELETE。</p>
     *
     * @return 变更类型（默认 UPDATE）
     */
    String changeType() default "UPDATE";

    /**
     * 变更字段名，写入 {@code api_change_log.field_name}。
     *
     * @return 字段名（默认空）
     */
    String fieldName() default "";

    /**
     * 变更字段中文名，写入 {@code api_change_log.field_label}。
     *
     * @return 字段中文名（默认空）
     */
    String fieldLabel() default "";
}
