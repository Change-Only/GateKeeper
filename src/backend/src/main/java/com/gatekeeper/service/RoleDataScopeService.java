package com.gatekeeper.service;

import com.gatekeeper.dto.RoleDataScopeSaveDto;
import com.gatekeeper.entity.SysRoleDataScope;
import com.gatekeeper.vo.DataScopeOptionsVo;
import com.gatekeeper.vo.RoleSimpleVo;

import java.util.List;

/**
 * 角色数据权限服务接口 — T05 perm-datascope 数据权限能力
 *
 * <p>数据权限语义：sys_role_datascope 空表 = 该角色「不限范围」。
 * {@link #saveRoleScopes} 为全量覆盖（先删后插）。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
public interface RoleDataScopeService {

    /**
     * 角色选择器列表（dataScope 取自 sys_role.data_scope）。
     *
     * @param keyword 角色名称关键字（可空）
     * @return 角色简表列表
     */
    List<RoleSimpleVo> listRolesForSelector(String keyword);

    /**
     * 数据范围选项：业务线 / 环境 / 接口分组。
     *
     * @return 选项 VO
     */
    DataScopeOptionsVo listOptions();

    /**
     * 查询某角色当前数据范围集合（空=不限）。
     *
     * @param roleId 角色 ID
     * @return 范围集合
     */
    List<SysRoleDataScope> listByRole(Long roleId);

    /**
     * 全量覆盖保存某角色数据范围（先删后插，scopes 为空=不限）。
     *
     * @param dto 入参（roleId 必填）
     */
    void saveRoleScopes(RoleDataScopeSaveDto dto);
}
