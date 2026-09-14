package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警规则表（alarm_rule）
 *
 * <p>alarm_type: FAIL_RATE/AUTH_FAIL/QUOTA_USAGE/AVG_LATENCY/KEY_EXPIRE/ZOMBIE_API/QPS_SURGE。
 * scope_type: 1=按对象(应用/接口)评估, 2=平台全局评估。
 * channel_ids/receiver_ids 以逗号分隔ID串存储，避免引入额外关联表（数据量 &lt;100 行）。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("alarm_rule")
public class AlarmRule {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则名称（原型 ruleName） */
    private String ruleName;

    /** 告警类型：FAIL_RATE/AUTH_FAIL/QUOTA_USAGE/AVG_LATENCY/KEY_EXPIRE/ZOMBIE_API/QPS_SURGE（原型 alarmType） */
    private String alarmType;

    /** 1=按对象(应用/接口), 2=平台全局（原型 scopeType） */
    private Integer scopeType;

    /**
     * 评估对象维度：APP=按应用 / API=按接口（T11 新增，把 scopeType=1「按对象」落到实处）。
     *
     * <p>仅 scopeType=1 时有意义且必填；scopeType=2（平台全局）时后端统一归一为 NULL。
     * 取值用 {@code AlarmRuleService.TARGET_TYPE_APP / TARGET_TYPE_API}。</p>
     */
    private String targetType;

    /**
     * 评估对象ID，逗号分隔（T11 新增）。
     *
     * <p>与 channelIds / receiverIds 同款约定（逗号串，不引关联表）。
     * <b>NULL 或空串 = 该维度下的全部对象</b> —— 因此种子规则可挂"全部接口"而不必枚举 ID。
     * 长度上限见 DDL（varchar(512)）。</p>
     */
    private String targetIds;

    /** 阈值表达式，如 >5 / >200%基线 / 提前30天（原型 threshold） */
    private String threshold;

    /** 统计窗口(分钟)（原型 timeWindow） */
    private Integer timeWindow;

    /** 告警等级：1=提示, 2=警告, 3=严重（原型 alarmLevel / dict alarm_level） */
    private Integer alarmLevel;

    /** 静默期(分钟)（原型 silencePeriod） */
    private Integer silencePeriod;

    /** 通知渠道ID，逗号分隔（原型 channelNames 归一化） */
    private String channelIds;

    /** ASSIGNEE=对象负责人, USER=指定用户, ROLE=指定角色 */
    private String receiverScope;

    /** 接收人ID，逗号分隔（原型 receiverNames 归一化） */
    private String receiverIds;

    /** 接收人描述，如 各应用负责人 */
    private String receiverDesc;

    /** 1=启用, 0=停用（原型 status） */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}