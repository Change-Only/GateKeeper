package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.dto.ApiEnvConfigDto;
import com.gatekeeper.entity.ApiEnvConfig;

import java.util.List;

/**
 * 接口环境配置服务接口 — T03b 接口生命周期子资源之一
 *
 * <p>提供接口环境配置（api_env_config）的 CRUD + Mock 开关：
 * <ul>
 *   <li>{@link #list} 按 apiId / envCode 筛选</li>
 *   <li>{@link #get} 详情（不存在抛 404）</li>
 *   <li>{@link #create} 创建（apiId / envCode / upstreamUrl 必填，按 uk_api_env_ver 唯一）</li>
 *   <li>{@link #update} 仅修改 upstreamUrl/connectTimeout/readTimeout/retryCount/mockEnabled，
 *        configStatus 自动重算</li>
 *   <li>{@link #toggleMock} 翻转 mockEnabled 0↔1</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
public interface ApiEnvConfigService extends IService<ApiEnvConfig> {

    /**
     * 按接口ID / 环境编码筛选环境配置列表。
     *
     * @param apiId   接口ID（可空）
     * @param envCode 环境编码（可空）
     * @return 环境配置 DTO 列表
     */
    List<ApiEnvConfigDto> list(Long apiId, String envCode);

    /**
     * 按主键查询环境配置详情。
     *
     * @param id 配置 ID
     * @return 环境配置 DTO（不存在抛 404）
     */
    ApiEnvConfigDto get(Long id);

    /**
     * 创建接口环境配置。
     *
     * @param dto 入参（apiId / envCode / upstreamUrl 必填）
     * @return 新建配置 DTO（含主键，configStatus 已推导）
     * @throws com.gatekeeper.exception.GatewayException 必填缺失/重复配置抛 400
     */
    ApiEnvConfigDto create(ApiEnvConfigDto dto);

    /**
     * 更新环境配置（仅可编辑字段，configStatus 自动重算）。
     *
     * @param id  配置 ID
     * @param dto 入参
     * @throws com.gatekeeper.exception.GatewayException 配置不存在抛 404
     */
    void update(Long id, ApiEnvConfigDto dto);

    /**
     * 翻转 Mock 开关（mockEnabled 0↔1）。
     *
     * @param id 配置 ID
     * @throws com.gatekeeper.exception.GatewayException 配置不存在抛 404
     */
    void toggleMock(Long id);
}
