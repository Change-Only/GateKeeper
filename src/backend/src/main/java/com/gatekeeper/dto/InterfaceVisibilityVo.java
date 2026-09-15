package com.gatekeeper.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口信息可见性白名单行 VO — T17
 *
 * <p>在白名单实体基础上补齐「人看得懂」的展示列：主体名称 / 角色编码。
 * 存储层只存 {@code (subject_type, subject_id)}，不能让运维对着一个裸 id 做决策。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
@Data
public class InterfaceVisibilityVo {

    /** 主键ID */
    private Long id;

    /** 主体类型：USER / ROLE */
    private String subjectType;

    /** 主体ID */
    private Long subjectId;

    /** 主体展示名：USER → 真实姓名（无则账号）；ROLE → 角色名称 */
    private String subjectLabel;

    /** 主体补充标识：USER → 登录账号；ROLE → 角色编码（如 SUPER_ADMIN） */
    private String subjectCode;

    /** 备注 */
    private String remark;

    /** 状态：1=启用，0=停用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 主体是否已不存在（用户/角色被删）。
     *
     * <p>刻意<b>不</b>在读取时自动清理这类行：删用户是另一条链路的事，
     * 在这里静默删除会让"谁改的、为什么少了一行"无从追溯。
     * 只把它标出来，由运维在页面上显式删除。</p>
     */
    private Boolean subjectMissing;
}
