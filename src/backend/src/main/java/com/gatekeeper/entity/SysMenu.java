package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜单权限点表（sys_menu）— 控制面权限基座
 *
 * <p>type: 1=模块（分组节点）, 2=菜单（页面）, 3=权限点（按钮/接口）。
 * 风险标识 risk_flag=1 为高危操作，需在审计日志中强制留痕（grant:revoke、app:credential:reset 等）。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("sys_menu")
public class SysMenu {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父级ID，0=顶级（原型 pid） */
    private Long pid;

    /** 菜单/权限点名称（原型 name） */
    private String name;

    /** 类型：1=模块, 2=菜单, 3=权限点（原型 type） */
    private Integer type;

    /** 权限点编码，如 app:create；模块节点为 NULL（原型 permCode） */
    private String permCode;

    /** 前端路由路径，type=2 页面节点使用，如 /app/list（承接原型 MENU_TREE 20 个页面） */
    private String routePath;

    /** 是否高危：1=高危, 0=普通（原型 risk） */
    private Integer riskFlag;

    /** 排序 */
    private Integer sortOrder;

    /** 状态：1=启用, 0=停用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}