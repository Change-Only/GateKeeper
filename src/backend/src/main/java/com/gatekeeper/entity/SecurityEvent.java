package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 安全事件表（security_event）— 安全检测规则触发后产生的告警/拦截事件
 * 记录事件类型、触发规则、处理状态与处理备注，供安全审计人员跟进处置
 */
@TableName("security_event")
public class SecurityEvent {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件类型（HIGH_FREQUENCY 高频调用 / ABNORMAL_TIME 异常时段 / AUTH_FAIL 鉴权失败 / ABNORMAL_PARAM 异常入参 / PERMISSION_BREACH 权限越界） */
    private String eventType;

    /** 事件描述 */
    private String eventDesc;

    /** 关联应用ID */
    private Long appId;

    /** 关联应用名 */
    private String appName;

    /** 触发事件的客户端IP */
    private String clientIp;

    /** 触发的规则 */
    private String triggerRule;

    /** 处理状态（0=待处理 1=已处理 2=已忽略） */
    private Integer handleStatus;

    /** 处理备注 */
    private String handleRemark;

    /** 事件发生时间 */
    private LocalDateTime occurredAt;

    /** 事件处理时间 */
    private LocalDateTime handledAt;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
