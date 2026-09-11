package com.gatekeeper.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.SysUser;
import com.gatekeeper.mapper.SysUserMapper;
import com.gatekeeper.service.SysUserService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 系统用户服务实现 — 负责后台用户的查询、创建、更新、启停、删除与密码重置，
 * 用户密码使用 BCrypt 单向加密后存储，返回实体时清除密码字段避免泄露。
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    /**
     * 分页查询系统用户列表，支持按用户名模糊匹配
     *
     * @param current  当前页码
     * @param size     每页条数
     * @param username 用户名（模糊匹配，可为空）
     * @return 用户分页结果
     */
    @Override
    public PageResult<SysUser> pageQuery(int current, int size, String username) {
        Page<SysUser> page = new Page<>(current, size);
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        if (username != null && !username.isEmpty()) {
            wrapper.like("username", username); // 用户名模糊匹配
        }
        wrapper.orderByDesc("created_at"); // 按创建时间倒序
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 创建用户：未指定密码时使用默认密码，密码经 BCrypt 加密后存储
     *
     * @param user 待创建的用户实体
     * @return 创建后的用户实体（密码字段已置空）
     */
    @Override
    public SysUser createUser(SysUser user) {
        if (user.getPassword() == null || user.getPassword().isEmpty()) {
            user.setPassword("123456"); // 默认初始密码
        }
        user.setPassword(BCrypt.hashpw(user.getPassword())); // BCrypt 加密存储
        user.setStatus(1); // 默认启用
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(user);
        user.setPassword(null); // 清除密码避免返回给前端
        return user;
    }

    /**
     * 更新用户信息（不更新密码）
     *
     * @param id   用户 ID
     * @param user 待更新的用户实体
     */
    @Override
    public void updateUser(Long id, SysUser user) {
        user.setId(id);
        user.setPassword(null); // 置空避免误更新密码
        user.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(user);
    }

    /**
     * 更新用户启停状态
     *
     * @param id     用户 ID
     * @param status 目标状态（1 启用 / 0 停用）
     */
    @Override
    public void updateStatus(Long id, Integer status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(user);
    }

    /**
     * 删除用户
     *
     * @param id 用户 ID
     */
    @Override
    public void deleteUser(Long id) {
        baseMapper.deleteById(id);
    }

    /**
     * 重置用户密码（BCrypt 加密后更新）
     *
     * @param id       用户 ID
     * @param password 新密码明文
     */
    @Override
    public void resetPassword(Long id, String password) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setPassword(BCrypt.hashpw(password)); // 新密码加密存储
        user.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(user);
    }
}
