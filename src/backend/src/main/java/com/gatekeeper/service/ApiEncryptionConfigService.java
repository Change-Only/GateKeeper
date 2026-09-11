package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.entity.ApiEncryptionConfig;

/**
 * 接口加解密配置服务接口 — 负责接口维度的加解密配置查询与保存业务
 */
public interface ApiEncryptionConfigService extends IService<ApiEncryptionConfig> {

    /**
     * 根据接口 ID 查询加解密配置
     *
     * @param interfaceId 接口 ID
     * @return 接口加解密配置实体
     */
    ApiEncryptionConfig getByInterfaceId(Long interfaceId);

    /**
     * 保存接口加解密配置
     *
     * @param config 加解密配置实体
     */
    void saveConfig(ApiEncryptionConfig config);
}
