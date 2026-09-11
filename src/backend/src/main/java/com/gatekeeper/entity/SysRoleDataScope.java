package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色数据权限范围表（sys_role_datascope）
 *
 * <p>scope_type: BIZ_LINE=业务线, ENV=环境, API_GROUP=接口分组。
 * 空表（该角色无任何范围记录）= 不限；避免 Null/全部二义性（架构 D4）。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("sys_role_datascope")
public class SysRoleDataScope {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色ID */
    private Long roleId;

    /** 范围类型：BIZ_LINE=业务线, ENV=环境, API_GROUP=接口分组 */
    private String scopeType;

    /** 范围值：业务线ID / 环境编码 / 接口分组ID */
    private String scopeValue;

    /** 创建时间 */
    private LocalDateTime createdAt;
}