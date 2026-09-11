package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.service.InterfaceService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 接口管理服务实现 — 负责 API 接口的分页查询、创建、更新、启停与删除，
 * 创建时为接口初始化启用状态并设置默认超时时间。
 */
@Service
public class InterfaceServiceImpl extends ServiceImpl<ApiInterfaceMapper, ApiInterface> implements InterfaceService {

    /**
     * 分页查询接口列表，支持按接口名称模糊匹配与分组筛选
     *
     * @param current       当前页码
     * @param size          每页条数
     * @param interfaceName 接口名称（模糊匹配，可为空）
     * @param groupId       接口分组 ID（可为空，为空则不筛选）
     * @return 接口分页结果
     */
    @Override
    public PageResult<ApiInterface> pageQuery(int current, int size, String interfaceName, Long groupId) {
        Page<ApiInterface> page = new Page<>(current, size);
        QueryWrapper<ApiInterface> wrapper = new QueryWrapper<>();
        if (interfaceName != null && !interfaceName.isEmpty()) {
            wrapper.like("interface_name", interfaceName); // 接口名称模糊匹配
        }
        if (groupId != null) {
            wrapper.eq("group_id", groupId); // 按分组精确筛选
        }
        wrapper.orderByDesc("created_at"); // 按创建时间倒序
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 创建接口：初始化启用状态，未指定超时时间时使用默认 5000ms
     *
     * @param apiInterface 待创建的接口实体
     * @return 创建后的接口实体
     */
    @Override
    public ApiInterface createInterface(ApiInterface apiInterface) {
        apiInterface.setStatus(1); // 默认启用
        if (apiInterface.getTimeoutMs() == null) {
            apiInterface.setTimeoutMs(5000); // 默认超时 5000 毫秒
        }
        apiInterface.setCreatedAt(LocalDateTime.now());
        apiInterface.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(apiInterface);
        return apiInterface;
    }

    /**
     * 更新接口信息
     *
     * @param id            接口 ID
     * @param apiInterface  待更新的接口实体
     */
    @Override
    public void updateInterface(Long id, ApiInterface apiInterface) {
        apiInterface.setId(id);
        apiInterface.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(apiInterface);
    }

    /**
     * 更新接口启停状态
     *
     * @param id     接口 ID
     * @param status 目标状态（1 启用 / 0 停用）
     */
    @Override
    public void updateStatus(Long id, Integer status) {
        ApiInterface iface = new ApiInterface();
        iface.setId(id);
        iface.setStatus(status);
        iface.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(iface);
    }

    /**
     * 删除接口
     *
     * @param id 接口 ID
     */
    @Override
    public void deleteInterface(Long id) {
        baseMapper.deleteById(id);
    }
}
