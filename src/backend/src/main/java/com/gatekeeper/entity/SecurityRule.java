package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 安全检测规则配置表（security_rule）— 安全防护的规则引擎配置
 * 定义高频调用、异常时段、鉴权失败、异常入参、权限越界等检测规则及触发后的处置动作
 */
@TableName("security_rule")
public class SecurityRule {
    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则名称 */
    private String ruleName;

    /** 规则类型 */
    private String ruleType;

    /** 规则配置（JSON 格式） */
    private String ruleConfig;

    /** 触发后的处置动作（ALERT 告警 / AUTO_BAN 自动封禁 / ALERT_AND_BAN 告警并封禁 / BLOCK 直接拦截） */
    private String triggerAction;

    /** 自动封禁时长（分钟） */
    private Integer banDurationMin;

    /** 是否启用 */
    private Boolean enabled;

    /** 规则描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
