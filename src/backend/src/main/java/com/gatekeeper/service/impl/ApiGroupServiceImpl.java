package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.service.ApiGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 接口分组服务实现 — 负责多层级树形接口分组的管理（查询、构建分组树、创建、更新、删除），
 * 并支持按分组（含其所有子分组）查询启用的接口列表。
 */
@Service
@RequiredArgsConstructor
public class ApiGroupServiceImpl extends ServiceImpl<ApiGroupMapper, ApiGroup> implements ApiGroupService {

    private final ApiInterfaceMapper interfaceMapper;

    /**
     * 查询全部分组列表（按排序值升序、创建时间倒序）
     *
     * @return 分组列表
     */
    @Override
    public List<ApiGroup> listGroups() {
        return baseMapper.selectList(
                new QueryWrapper<ApiGroup>().orderByAsc("sort_order").orderByDesc("created_at"));
    }

    /**
     * 构建多层级分组树：将扁平分组列表按 parentId 挂载为树形结构
     *
     * @return 顶层分组列表（每个分组含 children 子分组）
     */
    @Override
    public List<ApiGroup> listGroupTree() {
        List<ApiGroup> all = listGroups();
        Map<Long, ApiGroup> map = new HashMap<>();
        for (ApiGroup g : all) {
            map.put(g.getId(), g); // 建立 id -> 分组 的索引
        }
        List<ApiGroup> roots = new ArrayList<>();
        for (ApiGroup g : all) {
            if (g.getParentId() == null) {
                roots.add(g); // 无父分组，作为顶层节点
            } else {
                ApiGroup parent = map.get(g.getParentId());
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(g); // 挂载到父分组下
                } else {
                    roots.add(g); // 父分组缺失时降级为顶层节点
                }
            }
        }
        return roots;
    }

    /**
     * 查询指定分组及其所有子孙分组下已启用的接口列表
     *
     * @param groupId 分组 ID
     * @return 接口列表
     */
    @Override
    public List<ApiInterface> getInterfacesByGroup(Long groupId) {
        Set<Long> groupIds = new HashSet<>();
        collectGroupIds(groupId, groupIds); // 递归收集该分组及全部子分组 ID
        if (groupIds.isEmpty()) {
            return Collections.emptyList();
        }
        return interfaceMapper.selectList(
                new QueryWrapper<ApiInterface>()
                        .in("group_id", groupIds)
                        .eq("status", 1)
                        .orderByDesc("created_at"));
    }

    /**
     * 递归收集指定分组及其所有子孙分组的 ID 集合
     *
     * @param groupId 当前分组 ID
     * @param ids     已收集的分组 ID 集合
     */
    private void collectGroupIds(Long groupId, Set<Long> ids) {
        if (groupId == null || !ids.add(groupId)) {
            return; // 为空或已收集过则停止，避免循环
        }
        List<ApiGroup> children = baseMapper.selectList(
                new QueryWrapper<ApiGroup>().eq("parent_id", groupId));
        for (ApiGroup child : children) {
            collectGroupIds(child.getId(), ids); // 递归处理子分组
        }
    }

    /**
     * 创建接口分组
     *
     * @param group 待创建的分组实体
     * @return 创建后的分组实体
     */
    @Override
    public ApiGroup createGroup(ApiGroup group) {
        if (group.getSortOrder() == null) {
            group.setSortOrder(0); // 默认排序值为 0
        }
        group.setCreatedAt(LocalDateTime.now());
        group.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(group);
        return group;
    }

    /**
     * 更新接口分组
     *
     * @param id    分组 ID
     * @param group 待更新的分组实体
     */
    @Override
    public void updateGroup(Long id, ApiGroup group) {
        group.setId(id);
        group.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(group);
    }

    /**
     * 删除接口分组
     *
     * @param id 分组 ID
     */
    @Override
    public void deleteGroup(Long id) {
        baseMapper.deleteById(id);
    }
}
