package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 角色表（sys_role）— 系统的角色定义
 * 通过 sys_user_role 与用户建立多对多关联，实现基于角色的访问控制
 */
@TableName("sys_role")
public class SysRole {
    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色名称（唯一） */
    private String roleName;

    /** 角色编码（唯一，如 SUPER_ADMIN/OPERATOR/SECURITY_AUDITOR） */
    private String roleCode;

    /** 角色描述 */
    private String description;

    /** 角色状态（1=启用 0=停用） */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
