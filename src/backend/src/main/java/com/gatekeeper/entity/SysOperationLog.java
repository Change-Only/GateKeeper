package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 操作审计日志表（sys_operation_log）— 管理后台的操作审计记录
 * 记录操作者的增删改、登录登出等行为，用于安全审计与责任追溯
 */
@TableName("sys_operation_log")
public class SysOperationLog {
    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者用户ID */
    private Long operatorId;

    /** 操作者姓名 */
    private String operatorName;

    /** 操作类型（CREATE/UPDATE/DELETE/LOGIN/LOGOUT） */
    private String operationType;

    /** 操作模块（APP/INTERFACE/PERMISSION/SECURITY/SYSTEM） */
    private String operationModule;

    /** 操作描述 */
    private String operationDesc;

    /** 请求方法 */
    private String requestMethod;

    /** 请求URL */
    private String requestUrl;

    /** 请求参数 */
    private String requestParams;

    /** 客户端IP */
    private String clientIp;

    /** 操作耗时（毫秒） */
    private Integer costTime;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
