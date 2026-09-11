package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警表（alert）— 网关运行态的运营告警中心数据
 *
 * <p>与 security_event（安全审计事件）的区别：</p>
 * <ul>
 *   <li>security_event：安全规则触发的审计事件，由安全人员跟进处置；</li>
 *   <li>alert：覆盖网关运行全貌的运营告警（网关内部错误、限流、自动封禁、异常入参等），
 *       带<b>等级</b>（INFO/WARNING/CRITICAL）、<b>已读/未读</b>状态，并在管理端顶栏铃铛实时提示。</li>
 * </ul>
 */
@Data
@TableName("alert")
public class Alert {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 告警标题（一句话摘要） */
    private String title;

    /** 告警等级：INFO=提示 / WARNING=警告 / CRITICAL=严重 */
    private String level;

    /** 告警来源：GATEWAY=网关运行 / SECURITY=安全检测 / RATE_LIMIT=限流 / SYSTEM=系统 */
    private String source;

    /** 告警详细内容 */
    private String content;

    /** 关联应用ID（可为空） */
    private Long relatedAppId;

    /** 关联应用名（冗余，便于展示） */
    private String relatedAppName;

    /** 关联客户端IP（可为空） */
    private String relatedIp;

    /** 处理状态：0=未读, 1=已读, 2=已处理, 3=已忽略 */
    private Integer status;

    /** 处理备注 */
    private String handleRemark;

    /** 告警发生时间 */
    private LocalDateTime occurredAt;

    /** 已读时间 */
    private LocalDateTime readAt;

    /** 处理时间 */
    private LocalDateTime handledAt;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
