package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.entity.SysRole;
import com.gatekeeper.mapper.SysRoleMapper;
import com.gatekeeper.service.SysRoleService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色服务实现 — 负责系统角色的查询、创建、更新与删除，创建时默认启用。
 */
@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    /**
     * 查询全部角色列表（按创建时间倒序）
     *
     * @return 角色列表
     */
    @Override
    public List<SysRole> listRoles() {
        return baseMapper.selectList(
                new QueryWrapper<SysRole>().orderByDesc("created_at"));
    }

    /**
     * 创建角色
     *
     * @param role 待创建的角色实体
     * @return 创建后的角色实体
     */
    @Override
    public SysRole createRole(SysRole role) {
        role.setStatus(1); // 默认启用
        role.setCreatedAt(LocalDateTime.now());
        baseMapper.insert(role);
        return role;
    }

    /**
     * 更新角色
     *
     * @param id   角色 ID
     * @param role 待更新的角色实体
     */
    @Override
    public void updateRole(Long id, SysRole role) {
        role.setId(id);
        baseMapper.updateById(role);
    }

    /**
     * 删除角色
     *
     * @param id 角色 ID
     */
    @Override
    public void deleteRole(Long id) {
        baseMapper.deleteById(id);
    }
}
