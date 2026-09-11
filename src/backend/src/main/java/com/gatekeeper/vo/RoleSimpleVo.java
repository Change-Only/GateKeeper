package com.gatekeeper.vo;

import lombok.Data;

/**
 * 角色简表 VO — 数据权限角色选择器项（S1 接口返回形状）
 *
 * <p>dataScope 取自 sys_role.data_scope：ALL/BIZ_LINE/CUSTOM/SELF。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Data
public class RoleSimpleVo {

    /** 角色ID */
    private Long roleId;

    /** 角色名称 */
    private String roleName;

    /** 数据范围类型：ALL/BIZ_LINE/CUSTOM/SELF（取自 sys_role.data_scope） */
    private String dataScope;
}
