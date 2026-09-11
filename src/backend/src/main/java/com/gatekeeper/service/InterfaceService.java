package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.InterfaceDetailVo;
import com.gatekeeper.dto.InterfaceListVo;
import com.gatekeeper.entity.ApiInterface;

/**
 * 接口管理服务接口 — 负责网关接口信息的增删改查与状态管理业务
 *
 * <p>T03b 在存量能力之上扩展：
 * <ul>
 *   <li>{@link #pageQueryEnriched} 列表带分组名/业务线名（跨表冗余展示）</li>
 *   <li>{@link #getDetail} 详情聚合（基本信息 + 4 类参数 + 版本 + 环境配置 + 最近变更）</li>
 *   <li>{@link #deleteInterface} 删除前强制校验「所有版本已下线」</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
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
     * 分页查询接口列表（T03b 增强版）—— 行内带 groupName / lineName。
     *
     * @param current       当前页码
     * @param size          每页条数
     * @param interfaceName 接口名称（模糊查询，可为空）
     * @param groupId       接口分组 ID（可为空）
     * @return 增强后的接口分页结果
     */
    PageResult<InterfaceListVo> pageQueryEnriched(int current, int size, String interfaceName, Long groupId);

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
     * 删除接口（T03b：删除前必须已下线该接口全部版本）
     *
     * @param id 接口 ID
     * @throws com.gatekeeper.exception.GatewayException 存在未下线版本时抛 400
     */
    void deleteInterface(Long id);

    /**
     * 接口详情聚合（T03b）。
     *
     * @param id 接口 ID
     * @return 聚合 VO（基本信息 + 参数 4 类 + 版本 + 环境配置 + 最近 5 条变更）
     * @throws com.gatekeeper.exception.GatewayException 接口不存在抛 404
     */
    InterfaceDetailVo getDetail(Long id);
}
