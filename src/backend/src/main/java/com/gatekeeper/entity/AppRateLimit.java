package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 应用限流配置表（app_rate_limit）— 应用的流量控制策略
 * 与 app 一对一，配置 QPS、并发数、日调用量上限，超过阈值的请求将被网关限流
 */
@TableName("app_rate_limit")
public class AppRateLimit {
    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用ID（关联 app.id，唯一） */
    private Long appId;

    /** 每秒最大请求数（QPS 上限，0=不限） */
    private Integer qpsLimit;

    /** 最大并发数（0=不限） */
    private Integer concurrentLimit;

    /** 日调用量上限（0=不限） */
    private Integer dailyLimit;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
