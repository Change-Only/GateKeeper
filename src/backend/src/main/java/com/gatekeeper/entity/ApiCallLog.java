package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 调用日志表（api_call_log）— 网关转发的调用记录
 * 记录每次请求的调用方、接口、入参、响应、耗时、限流与拦截情况，数据量大时按月归档/清理
 */
@TableName("api_call_log")
public class ApiCallLog {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调用方应用ID */
    private Long appId;

    /** 调用方应用名（冗余存储，便于查询展示） */
    private String appName;

    /** 请求接口ID */
    private Long interfaceId;

    /** 请求接口路径 */
    private String interfacePath;

    /** 请求方法 */
    private String requestMethod;

    /** 请求时间 */
    private LocalDateTime requestTime;

    /** 请求入参（加密接口存密文） */
    private String requestParams;

    /** 响应数据（加密接口存密文） */
    private String responseData;

    /** HTTP 响应状态码 */
    private Integer responseStatus;

    /** 请求耗时（毫秒） */
    private Integer costTime;

    /** 调用方IP */
    private String clientIp;

    /** 本次调用使用的加密算法 */
    private String encryptionAlgorithm;

    /** 是否被限流 */
    private Boolean isRateLimited;

    /** 是否被拦截 */
    private Boolean isBlocked;

    /** 拦截原因 */
    private String blockReason;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
