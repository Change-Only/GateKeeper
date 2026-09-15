package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统级访问白名单表（sys_ip_whitelist） — T15-4
 *
 * <p><b>与 {@link AppIpWhitelist}（应用级）的区别是本类存在的全部理由</b>：
 * <ul>
 *   <li>{@link AppIpWhitelist} —— 「**这个应用**的调用方来源限制」，随应用走，
 *       在应用详情里维护，只作用于该应用的接口；</li>
 *   <li>{@link SysIpWhitelist}（本类）—— 「**本系统**的入口来源限制」，
 *       网关责任链最前（{@code @Order(0)}）的全局前置校验，
 *       一旦有启用条目，**任何应用**的接口都只对这些来源开放。</li>
 * </ul>
 * 两层是「且」的关系：来源 IP 必须同时通过系统级与应用级两道白名单。</p>
 *
 * <p><b>生效规则（务必与 {@code SysAccessWhitelistHandler} 保持一致）</b>：
 * <ol>
 *   <li>表为空，或无 {@code status=1} 的行 ⇒ <b>不限制</b>（与改造前行为完全一致）；</li>
 *   <li>存在 {@code status=1} 的行 ⇒ 命中任一 CIDR 才放行；</li>
 *   <li>查询异常 ⇒ <b>fail-open 放行</b> + WARN（不因配置面故障拖垮整个网关）。</li>
 * </ol>
 * 「空表即不限制」是本次改造对存量环境<b>零影响</b>的关键：
 * 迁移脚本刻意不播种任何行，避免一上线就把测试机或管理员自己挡在门外。</p>
 *
 * @author GateKeeper
 * @since T15-4 (2026-09-15)
 */
@Data
@TableName("sys_ip_whitelist")
public class SysIpWhitelist {

    /** 状态：启用（参与校验） */
    public static final int STATUS_ENABLED = 1;

    /** 状态：停用（不参与校验，便于临时摘除而不丢记录） */
    public static final int STATUS_DISABLED = 0;

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 允许访问的 IP 或 CIDR 网段（唯一；如 10.0.0.1 或 10.0.0.0/24） */
    private String ipCidr;

    /** 备注（说明为何放行，便于他人理解） */
    private String remark;

    /** 状态：1=启用，0=停用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
