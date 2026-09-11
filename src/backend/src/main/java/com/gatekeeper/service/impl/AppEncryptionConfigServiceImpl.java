package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.entity.AppEncryptionConfig;
import com.gatekeeper.mapper.AppEncryptionConfigMapper;
import com.gatekeeper.service.AppEncryptionConfigService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 应用加解密配置服务实现 — 负责按应用查询加解密配置，并保存/更新应用级加解密配置
 * （同一应用只保留一份配置，存在则更新、不存在则新增）。
 */
@Service
public class AppEncryptionConfigServiceImpl extends ServiceImpl<AppEncryptionConfigMapper, AppEncryptionConfig>
        implements AppEncryptionConfigService {

    /**
     * 按应用 ID 查询加解密配置
     *
     * @param appId 应用 ID
     * @return 加解密配置实体，不存在时返回 null
     */
    @Override
    public AppEncryptionConfig getByAppId(Long appId) {
        return baseMapper.selectOne(
                new QueryWrapper<AppEncryptionConfig>().eq("app_id", appId));
    }

    /**
     * 保存应用加解密配置：存在则更新，不存在则新增
     *
     * @param config 加解密配置实体
     */
    @Override
    public void saveConfig(AppEncryptionConfig config) {
        AppEncryptionConfig existing = baseMapper.selectOne(
                new QueryWrapper<AppEncryptionConfig>().eq("app_id", config.getAppId()));
        if (existing != null) {
            config.setId(existing.getId()); // 复用已有记录 ID，执行更新
            config.setUpdatedAt(LocalDateTime.now());
            baseMapper.updateById(config);
        } else {
            config.setCreatedAt(LocalDateTime.now());
            config.setUpdatedAt(LocalDateTime.now());
            baseMapper.insert(config);
        }
    }
}
