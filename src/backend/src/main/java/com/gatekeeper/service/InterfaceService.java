package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.ApiInterface;

/**
 * 接口管理服务接口 — 负责网关接口信息的增删改查与状态管理业务
 */
public interface InterfaceService extends IService<ApiInterface> {

    /**
     * 分页查询接口列表
     *
     * @param current       当前页码
     * @param size          每页条数
     * @param interfaceName 接口名称（模糊查询，可为空）
     * @param groupId       接口分组 ID（可为空）
     * @return 接口分页结果
     */
    PageResult<ApiInterface> pageQuery(int current, int size, String interfaceName, Long groupId);

    /**
     * 创建接口
     *
     * @param apiInterface 接口实体（含路径、名称、分组等信息）
     * @return 创建后的接口实体
     */
    ApiInterface createInterface(ApiInterface apiInterface);

    /**
     * 更新接口信息
     *
     * @param id           接口 ID
     * @param apiInterface 待更新的接口实体
     */
    void updateInterface(Long id, ApiInterface apiInterface);

    /**
     * 更新接口启用/停用状态
     *
     * @param id     接口 ID
     * @param status 目标状态值
     */
    void updateStatus(Long id, Integer status);

    /**
     * 删除接口
     *
     * @param id 接口 ID
     */
    void deleteInterface(Long id);
}
