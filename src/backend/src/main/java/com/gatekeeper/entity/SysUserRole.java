package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 用户角色关联表（sys_user_role）— 用户与角色的多对多中间表
 */
@TableName("sys_user_role")
public class SysUserRole {
    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID（关联 sys_user.id） */
    private Long userId;

    /** 角色ID（关联 sys_role.id） */
    private Long roleId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
