package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.entity.SysRole;

import java.util.List;

/**
 * 角色服务接口 — 负责系统角色的查询、创建、更新与删除业务
 */
public interface SysRoleService extends IService<SysRole> {

    /**
     * 查询全部角色
     *
     * @return 角色列表
     */
    List<SysRole> listRoles();

    /**
     * 创建角色
     *
     * @param role 角色实体
     * @return 创建后的角色实体
     */
    SysRole createRole(SysRole role);

    /**
     * 更新角色
     *
     * @param id   角色 ID
     * @param role 待更新的角色实体
     */
    void updateRole(Long id, SysRole role);

    /**
     * 删除角色
     *
     * @param id 角色 ID
     */
    void deleteRole(Long id);
}
