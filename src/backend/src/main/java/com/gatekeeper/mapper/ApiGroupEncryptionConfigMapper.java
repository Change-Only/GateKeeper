package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.ApiGroupEncryptionConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口分组加解密配置 Mapper — 负责 api_group_encryption_config 表的数据访问。
 *
 * <p>T15-1：分组级加解密配置。表有唯一键 {@code uk_gec_group(group_id)}，
 * 因此按 group_id 查询单条是安全的（不会出现 TooManyResultsException）。</p>
 *
 * @author GateKeeper
 * @since T15-1 (2026-09-15)
 */
@Mapper
public interface ApiGroupEncryptionConfigMapper extends BaseMapper<ApiGroupEncryptionConfig> {
}
