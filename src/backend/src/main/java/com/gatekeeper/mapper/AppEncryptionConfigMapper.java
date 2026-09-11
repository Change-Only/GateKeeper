package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.AppEncryptionConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 应用加解密配置表 Mapper — 负责 app_encryption_config 表的数据访问
 */
@Mapper
public interface AppEncryptionConfigMapper extends BaseMapper<AppEncryptionConfig> {
}
