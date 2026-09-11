package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.entity.ApiVersion;

import java.util.List;

/**
 * 接口版本服务接口 — T03b 接口生命周期子资源之一
 *
 * <p>提供接口版本（api_version）的 CRUD + 当前版本 + 生命周期流转：
 * <ul>
 *   <li>{@link #list} 按 apiId 筛选</li>
 *   <li>{@link #current} 返回当前默认版本（is_current=1，EXACTLY ONE）</li>
 *   <li>{@link #get} 详情（不存在抛 404）</li>
 *   <li>{@link #create} 创建（apiId / version 必填，按 uk_version_api 唯一）</li>
 *   <li>{@link #setCurrent} 设为当前版本（事务：其余版本 is_current 清零，本版本置 1）</li>
 *   <li>{@link #publish} 发布版本（校验已验证环境配置 → 置 current + 同步接口发布态）</li>
 *   <li>{@link #setGray} 设置灰度比例（0-100，仅当前版本）</li>
 *   <li>{@link #deprecate} 弃用（status=2, deprecateTime=now）</li>
 *   <li>{@link #offline} 下线（status=3）</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
public interface ApiVersionService extends IService<ApiVersion> {

    /**
     * 按接口ID筛选版本列表。
     *
     * @param apiId 接口ID（可空）
     * @return 版本 DTO 列表
     */
    List<ApiVersionDto> list(Long apiId);

    /**
     * 按主键查询版本详情。
     *
     * @param id 版本 ID
     * @return 版本 DTO（不存在抛 404）
     */
    ApiVersionDto get(Long id);

    /**
     * 查询某接口的当前默认版本（is_current=1，取第一个）。
     *
     * @param apiId 接口ID
     * @return 当前版本 DTO（无则返回 null）
     */
    ApiVersionDto current(Long apiId);

    /**
     * 创建接口版本。
     *
     * @param dto 入参（apiId / version 必填，同一接口 version 唯一）
     * @return 新建版本 DTO（含主键）
     * @throws com.gatekeeper.exception.GatewayException 必填缺失/重复版本抛 400
     */
    ApiVersionDto create(ApiVersionDto dto);

    /**
     * 设为当前默认版本（事务，强制 EXACTLY ONE current）。
     *
     * @param id 版本 ID
     * @throws com.gatekeeper.exception.GatewayException 版本不存在抛 404
     */
    void setCurrent(Long id);

    /**
     * 发布版本（T03b 核心）。
     *
     * <p>业务规则：
     * <ol>
     *   <li>发布前必须已配置至少 1 个「已验证」环境（api_env_config.config_status=2）</li>
     *   <li>本版本 is_current=1，同接口其余版本 is_current=0</li>
     *   <li>本版本 status=1（生效中）</li>
     *   <li>同步更新 api_interface.publish_status=2（已发布）与 current_version</li>
     * </ol></p>
     *
     * @param id 版本 ID
     * @return 发布后的版本 DTO（含 apiId，供变更留痕解析）
     * @throws com.gatekeeper.exception.GatewayException 版本不存在抛 404；无已验证环境抛 400
     */
    ApiVersionDto publish(Long id);

    /**
     * 设置灰度比例（T04 网关按 appId 稳定哈希分流）。
     *
     * @param id        版本 ID
     * @param grayRatio 灰度百分比 0-100
     * @return 更新后的版本 DTO
     * @throws com.gatekeeper.exception.GatewayException 版本不存在抛 404；比例越界/非当前版本抛 400
     */
    ApiVersionDto setGray(Long id, Integer grayRatio);

    /**
     * 弃用版本（status=2, deprecateTime=now）。
     *
     * @param id 版本 ID
     */
    void deprecate(Long id);

    /**
     * 下线版本（status=3）。
     *
     * @param id 版本 ID
     */
    void offline(Long id);
}
