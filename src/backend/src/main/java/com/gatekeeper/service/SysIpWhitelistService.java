package com.gatekeeper.service;

import com.gatekeeper.entity.SysIpWhitelist;

import java.util.List;

/**
 * 系统级访问白名单服务 — T15-4
 *
 * <p>维护入口唯一（应用管理页「系统访问白名单」），
 * 校验侧只读 {@link #listEnabled()}，由网关 {@code SysAccessWhitelistHandler} 调用。</p>
 *
 * @author GateKeeper
 * @since T15-4 (2026-09-15)
 */
public interface SysIpWhitelistService {

    /**
     * 查询全部条目（启用在前，其次按创建时间倒序），供管理页面展示。
     *
     * @return 条目列表（可能为空）
     */
    List<SysIpWhitelist> list();

    /**
     * 查询**启用中**的条目，供网关校验使用。
     *
     * <p>返回值用于判断「是否启用系统级白名单」：空列表 ⇒ 不限制。</p>
     *
     * @return status=1 的条目列表（可能为空）
     */
    List<SysIpWhitelist> listEnabled();

    /**
     * 新增一条白名单。
     *
     * @param entry 条目（ipCidr 必填；status 为空时默认启用）
     */
    void add(SysIpWhitelist entry);

    /**
     * 按行 ID 更新（整行覆盖：IP/CIDR、备注、状态）。
     *
     * @param id    条目 ID
     * @param entry 新值
     */
    void updateById(Long id, SysIpWhitelist entry);

    /**
     * 删除一条白名单。
     *
     * @param id 条目 ID
     */
    void deleteById(Long id);
}
