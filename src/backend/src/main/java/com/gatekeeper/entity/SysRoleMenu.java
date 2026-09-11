package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-权限点关联表（sys_role_menu）
 *
 * <p>关联 sys_role 与 sys_menu（type=3），是 @RequirePerm 注解校验的数据源，
 * 登录时通过 sysRoleMenuMapper.selectPermCodesByUserId 拉权限集合，写入 Redis gk:perm:{userId}。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("sys_role_menu")
public class SysRoleMenu {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色ID（原型 rolePerms 的 key） */
    private Long roleId;

    /** 权限点ID（原型 rolePerms 的 value 数组） */
    private Long menuId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}