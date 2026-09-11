package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.AppApiPermission;
import com.gatekeeper.mapper.AppApiPermissionMapper;
import com.gatekeeper.service.ApiGroupService;
import com.gatekeeper.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 权限管理服务实现 — 负责应用与接口之间调用权限的查询、授权（单条/批量/按分组）与回收，
 * 权限记录通过 status 字段区分有效（1）与已回收（0）。
 */
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl extends ServiceImpl<AppApiPermissionMapper, AppApiPermission> implements PermissionService {

    private final ApiGroupService apiGroupService;

    /**
     * 查询有效权限列表，可按应用或接口维度过滤
     *
     * @param appId       应用 ID（可为空）
     * @param interfaceId 接口 ID（可为空）
     * @return 有效权限列表
     */
    @Override
    public List<AppApiPermission> listPermissions(Long appId, Long interfaceId) {
        QueryWrapper<AppApiPermission> wrapper = new QueryWrapper<>();
        if (appId != null) {
            wrapper.eq("app_id", appId); // 按应用过滤
        }
        if (interfaceId != null) {
            wrapper.eq("interface_id", interfaceId); // 按接口过滤
        }
        wrapper.eq("status", 1); // 只查有效权限
        return baseMapper.selectList(wrapper);
    }

    /**
     * 授权单个接口：若已有权限记录则将其置为有效，否则新建授权记录
     *
     * @param permission 授权实体（含 appId 与 interfaceId）
     */
    @Override
    public void grant(AppApiPermission permission) {
        AppApiPermission existing = baseMapper.selectOne(
                new QueryWrapper<AppApiPermission>()
                        .eq("app_id", permission.getAppId())
                        .eq("interface_id", permission.getInterfaceId()));
        if (existing != null) {
            existing.setStatus(1); // 已存在则恢复为有效
            existing.setUpdatedAt(LocalDateTime.now());
            baseMapper.updateById(existing);
        } else {
            permission.setStatus(1);
            permission.setCreatedAt(LocalDateTime.now());
            permission.setUpdatedAt(LocalDateTime.now());
            baseMapper.insert(permission);
        }
    }

    /**
     * 批量为应用授权多个接口
     *
     * @param appId        应用 ID
     * @param interfaceIds 接口 ID 列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchGrant(Long appId, List<Long> interfaceIds) {
        for (Long interfaceId : interfaceIds) {
            AppApiPermission p = new AppApiPermission();
            p.setAppId(appId);
            p.setInterfaceId(interfaceId);
            grant(p); // 逐个授权，事务保证整体回滚
        }
    }

    /**
     * 按分组授权：将分组下（含子分组）所有启用接口授权给应用
     *
     * @param appId   应用 ID
     * @param groupId 接口分组 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grantByGroup(Long appId, Long groupId) {
        List<ApiInterface> interfaces = apiGroupService.getInterfacesByGroup(groupId);
        for (ApiInterface iface : interfaces) {
            AppApiPermission p = new AppApiPermission();
            p.setAppId(appId);
            p.setInterfaceId(iface.getId());
            grant(p); // 对分组内每个接口执行授权
        }
    }

    /**
     * 回收权限：将应用对指定接口的权限置为无效（逻辑删除）
     *
     * @param appId       应用 ID
     * @param interfaceId 接口 ID
     */
    @Override
    public void revoke(Long appId, Long interfaceId) {
        AppApiPermission existing = baseMapper.selectOne(
                new QueryWrapper<AppApiPermission>()
                        .eq("app_id", appId)
                        .eq("interface_id", interfaceId));
        if (existing != null) {
            existing.setStatus(0); // 置为无效
            existing.setUpdatedAt(LocalDateTime.now());
            baseMapper.updateById(existing);
        }
    }
}
