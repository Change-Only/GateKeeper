package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.App;
import com.gatekeeper.entity.AppIpWhitelist;
import com.gatekeeper.entity.AppRateLimit;

import java.util.List;

/**
 * 应用管理服务接口 — 负责应用信息的增删改查、IP 白名单管理、限流配置以及密钥重置业务
 */
public interface AppService extends IService<App> {

    /**
     * 分页查询应用列表
     *
     * @param current 当前页码
     * @param size    每页条数
     * @param appName 应用名称（模糊查询，可为空）
     * @param status  应用状态（可为空）
     * @return 应用分页结果
     */
    PageResult<App> pageQuery(int current, int size, String appName, Integer status);

    /**
     * 创建应用
     *
     * @param app 应用实体（含名称、描述、状态等信息）
     * @return 创建后的应用实体（含系统生成的密钥等信息）
     */
    App createApp(App app);

    /**
     * 更新应用信息
     *
     * @param id  应用 ID
     * @param app 待更新的应用实体
     */
    void updateApp(Long id, App app);

    /**
     * 更新应用启用/停用状态
     *
     * @param id     应用 ID
     * @param status 目标状态值
     */
    void updateStatus(Long id, Integer status);

    /**
     * 删除应用
     *
     * @param id 应用 ID
     */
    void deleteApp(Long id);

    /**
     * 查询应用的 IP 白名单列表
     *
     * @param appId 应用 ID
     * @return IP 白名单列表
     */
    List<AppIpWhitelist> listIpWhitelist(Long appId);

    /**
     * 新增应用的 IP 白名单条目
     *
     * @param appId     应用 ID
     * @param whitelist IP 白名单实体
     */
    void addIpWhitelist(Long appId, AppIpWhitelist whitelist);

    /**
     * 更新应用的 IP 白名单条目（T15-4：补编辑与启用/停用）
     *
     * <p>归属应用不可改；{@code status} 为空时按启用处理。
     * 停用后该条目不参与网关校验（{@code IpWhitelistHandler} 按 status=1 过滤）。</p>
     *
     * @param whitelistId 白名单记录 ID
     * @param whitelist   新值
     */
    void updateIpWhitelist(Long whitelistId, AppIpWhitelist whitelist);

    /**
     * 删除指定 IP 白名单条目
     *
     * @param whitelistId 白名单记录 ID
     */
    void removeIpWhitelist(Long whitelistId);

    /**
     * 查询应用的限流配置
     *
     * @param appId 应用 ID
     * @return 应用的限流配置实体
     */
    AppRateLimit getRateLimit(Long appId);

    /**
     * 更新应用的限流配置
     *
     * @param appId     应用 ID
     * @param rateLimit 限流配置实体
     */
    void updateRateLimit(Long appId, AppRateLimit rateLimit);
}
