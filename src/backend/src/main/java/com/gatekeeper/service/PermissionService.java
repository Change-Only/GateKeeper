package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.entity.AppApiPermission;

import java.util.List;

/**
 * 权限管理服务接口 — 负责应用与接口之间授权关系的查询、授予与回收业务
 */
public interface PermissionService extends IService<AppApiPermission> {

    /**
     * 按应用或接口条件查询授权关系
     *
     * @param appId       应用 ID（可为空）
     * @param interfaceId 接口 ID（可为空）
     * @return 授权关系列表
     */
    List<AppApiPermission> listPermissions(Long appId, Long interfaceId);

    /**
     * 授予单个接口访问权限
     *
     * @param permission 授权关系实体（含应用 ID 与接口 ID）
     */
    void grant(AppApiPermission permission);

    /**
     * 批量授予多个接口的访问权限
     *
     * @param appId         应用 ID
     * @param interfaceIds  待授权的接口 ID 列表
     */
    void batchGrant(Long appId, List<Long> interfaceIds);

    /**
     * 按分组授权（含子分组的全部接口）
     *
     * @param appId   应用 ID
     * @param groupId 分组 ID
     */
    void grantByGroup(Long appId, Long groupId);

    /**
     * 回收应用对某接口的访问权限
     *
     * @param appId       应用 ID
     * @param interfaceId 接口 ID
     */
    void revoke(Long appId, Long interfaceId);
}
