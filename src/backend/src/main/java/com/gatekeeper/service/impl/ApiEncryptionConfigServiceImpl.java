package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.entity.ApiEncryptionConfig;
import com.gatekeeper.mapper.ApiEncryptionConfigMapper;
import com.gatekeeper.service.ApiEncryptionConfigService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 接口加解密配置服务实现 — 负责按接口查询加解密配置，并保存/更新接口级加解密配置
 * （同一接口只保留一份配置，存在则更新、不存在则新增）。
 */
@Service
public class ApiEncryptionConfigServiceImpl extends ServiceImpl<ApiEncryptionConfigMapper, ApiEncryptionConfig>
        implements ApiEncryptionConfigService {

    /**
     * 按接口 ID 查询加解密配置
     *
     * @param interfaceId 接口 ID
     * @return 加解密配置实体，不存在时返回 null
     */
    @Override
    public ApiEncryptionConfig getByInterfaceId(Long interfaceId) {
        return baseMapper.selectOne(
                new QueryWrapper<ApiEncryptionConfig>().eq("interface_id", interfaceId));
    }

    /**
     * 保存接口加解密配置：存在则更新，不存在则新增
     *
     * @param config 加解密配置实体
     */
    @Override
    public void saveConfig(ApiEncryptionConfig config) {
        ApiEncryptionConfig existing = baseMapper.selectOne(
                new QueryWrapper<ApiEncryptionConfig>().eq("interface_id", config.getInterfaceId()));
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
