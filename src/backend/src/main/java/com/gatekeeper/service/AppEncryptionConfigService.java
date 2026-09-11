package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.entity.AppEncryptionConfig;

/**
 * 应用加解密配置服务接口 — 负责应用维度的加解密配置查询与保存业务
 */
public interface AppEncryptionConfigService extends IService<AppEncryptionConfig> {

    /**
     * 根据应用 ID 查询加解密配置
     *
     * @param appId 应用 ID
     * @return 应用加解密配置实体
     */
    AppEncryptionConfig getByAppId(Long appId);

    /**
     * 保存应用加解密配置
     *
     * @param config 加解密配置实体
     */
    void saveConfig(AppEncryptionConfig config);
}
