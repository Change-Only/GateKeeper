package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.ApiEncryptionConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口加解密配置表 Mapper — 负责 api_encryption_config 表的数据访问
 */
@Mapper
public interface ApiEncryptionConfigMapper extends BaseMapper<ApiEncryptionConfig> {
}
