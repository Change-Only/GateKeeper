package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiInterface;

import java.util.List;

/**
 * 接口分组服务接口 — 负责接口分组的层级管理及分组下接口的查询业务（支持多层级）
 */
public interface ApiGroupService extends IService<ApiGroup> {

    /**
     * 查询全部分组（扁平结构）
     *
     * @return 分组列表
     */
    List<ApiGroup> listGroups();

    /**
     * 查询分组树形结构
     *
     * @return 树形分组结构列表
     */
    List<ApiGroup> listGroupTree();

    /**
     * 递归查询某分组（含子分组）下的所有接口
     *
     * @param groupId 分组 ID
     * @return 该分组及其子分组下的全部接口列表
     */
    List<ApiInterface> getInterfacesByGroup(Long groupId);

    /**
     * 创建分组
     *
     * @param group 分组实体（含父分组 ID 等）
     * @return 创建后的分组实体
     */
    ApiGroup createGroup(ApiGroup group);

    /**
     * 更新分组
     *
     * @param id    分组 ID
     * @param group 待更新的分组实体
     */
    void updateGroup(Long id, ApiGroup group);

    /**
     * 删除分组
     *
     * @param id 分组 ID
     */
    void deleteGroup(Long id);
}
