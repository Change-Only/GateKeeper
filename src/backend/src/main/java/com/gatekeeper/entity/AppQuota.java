package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用配额表（app_quota）— 按环境的多档配额
 *
 * <p>替代存量 app_rate_limit 的「应用级单档限流」。
 * 网关 RateLimitHandler 优先读本表，未配置时回退 app_rate_limit（架构 D2）。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("app_quota")
public class AppQuota {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用ID（原型 appId） */
    private Long appId;

    /** 环境编码（原型 envCode，默认 prod） */
    private String envCode;

    /** 全局QPS上限，0=不限（原型 globalQps） */
    private Integer globalQps;

    /** 日调用配额，0=不限（原型 dailyQuota） */
    private Long dailyQuota;

    /** 月调用配额，0=不限（原型 monthlyQuota） */
    private Long monthlyQuota;

    /** 并发上限，0=不限（原型 concurrency） */
    private Integer concurrency;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}