package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.SysUser;

/**
 * 系统用户服务接口 — 负责系统后台用户的增删改查、状态管理与密码重置业务
 */
public interface SysUserService extends IService<SysUser> {

    /**
     * 分页查询系统用户列表
     *
     * @param current  当前页码
     * @param size     每页条数
     * @param username 用户名（模糊查询，可为空）
     * @return 系统用户分页结果
     */
    PageResult<SysUser> pageQuery(int current, int size, String username);

    /**
     * 创建系统用户
     *
     * @param user 系统用户实体（含用户名、密码、角色等信息）
     * @return 创建后的系统用户实体
     */
    SysUser createUser(SysUser user);

    /**
     * 更新系统用户信息
     *
     * @param id   用户 ID
     * @param user 待更新的用户实体
     */
    void updateUser(Long id, SysUser user);

    /**
     * 更新系统用户启用/停用状态
     *
     * @param id     用户 ID
     * @param status 目标状态值
     */
    void updateStatus(Long id, Integer status);

    /**
     * 删除系统用户
     *
     * @param id 用户 ID
     */
    void deleteUser(Long id);

    /**
     * 重置用户密码
     *
     * @param id       用户 ID
     * @param password 新密码
     */
    void resetPassword(Long id, String password);
}
