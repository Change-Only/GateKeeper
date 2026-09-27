package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.SysConfigDto;
import com.gatekeeper.entity.SysConfig;

/**
 * 系统参数配置服务接口 — T05 sys-config 参数配置能力
 *
 * <p>提供 sys_config 的分页 / 全量 / 详情 / 新建 / 更新 / 删除：
 * <ul>
 *   <li>{@link #pageQuery} 支持按 config_key/config_name/config_group 模糊、按 configGroup 精确筛选</li>
 *   <li>{@link #getConfig} 返回前对 sensitive=1 的配置做 config_value 脱敏</li>
 *   <li>{@link #createConfig} 创建前校验 config_key 唯一</li>
 *   <li>{@link #deleteConfig} built_in=1 拒绝删除</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
public interface ConfigService extends IService<SysConfig> {

    /**
     * 分页查询配置列表。
     *
     * @param pageNum     当前页码（从 1 开始）
     * @param pageSize    每页条数
     * @param keyword     关键字（模糊匹配 config_key/config_name/config_group，可空）
     * @param configGroup 配置分组筛选（可空）
     * @param status      状态筛选（可空；注：sys_config 无 status 列，当前不参与过滤，保留作前端兼容）
     * @return 分页结果（sensitive=1 的 config_value 已脱敏）
     */
    PageResult<SysConfig> pageQuery(int pageNum, int pageSize, String keyword, String configGroup, Integer status);
    /**
     * 新建配置。
     *
     * @param dto 入参（configKey 必填）
     * @return 创建后的配置（含主键，config_value 已脱敏）
     * @throws com.gatekeeper.exception.GatewayException configKey 已存在 / 字段非法
     */
    SysConfig createConfig(SysConfigDto dto);

    /**
     * 更新配置（id 必填，builtIn 内置标识不可改）。
     *
     * @param dto 入参
     */
    void updateConfig(SysConfigDto dto);

    /**
     * 删除配置：built_in=1 拒绝。
     *
     * @param id 配置 ID
     */
    void deleteConfig(Long id);
}
