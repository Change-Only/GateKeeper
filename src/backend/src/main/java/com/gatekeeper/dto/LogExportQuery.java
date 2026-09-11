package com.gatekeeper.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调用日志导出筛选条件 — 异步导出任务的参数载体
 *
 * <p>字段与 {@code /log/list} 的筛选条件一致，导出任务创建时固化一份条件快照，
 * 后台线程按该条件分批拉取数据，避免导出过程中页面条件变化影响结果。</p>
 */
@Data
public class LogExportQuery {

    /** 应用 ID（可选） */
    private Long appId;

    /** 接口 ID（可选） */
    private Long interfaceId;

    /** 响应状态码（可选） */
    private Integer responseStatus;

    /** 客户端 IP（可选） */
    private String clientIp;

    /** 是否被限流（可选） */
    private Boolean isRateLimited;

    /** 是否被拦截（可选） */
    private Boolean isBlocked;

    /** 请求起始时间（可选） */
    private LocalDateTime startTime;

    /** 请求结束时间（可选） */
    private LocalDateTime endTime;
}
