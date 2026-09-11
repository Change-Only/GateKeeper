package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 动态封禁规则表（block_rule）— 风控「封禁」规则
 *
 * <p>与存量 security_rule 的「检测」职责分离：security_rule 负责产生 security_event，
 * block_rule 负责命中后写入 ip_ban/封禁名单。
 *
 * <p>enabled 默认 0：除人工封禁外，自动封禁规则默认关闭，上线需人工确认阈值。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("block_rule")
public class BlockRule {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** IP=按来源IP, APP=按应用/AppKey（原型 scope） */
    private String scope;

    /** REPLAY_ATTACK/SIGNATURE_MISMATCH/RATE_LIMIT_EXCEEDED/IP_NOT_ALLOWED/MANUAL（原型 reasonCode） */
    private String reasonCode;

    /** 阈值描述，如 同IP 5min ≥ 20次（原型 threshold） */
    private String thresholdDesc;

    /** 窗口内触发次数阈值 */
    private Integer thresholdCount;

    /** 统计窗口(分钟) */
    private Integer windowMinutes;

    /** 封禁时长(秒)，0=永久（原型 ttl 结构化） */
    private Integer ttlSeconds;

    /** 1=自动封禁, 0=人工触发（原型 auto） */
    private Integer autoBlock;

    /** 1=启用, 0=停用（原型 enabled） */
    private Integer enabled;

    /** 规则说明（原型 desc） */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}