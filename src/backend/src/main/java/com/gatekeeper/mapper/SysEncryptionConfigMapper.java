package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SysEncryptionConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 平台级加解密总开关 Mapper（单行表，恒取 id=1）— T16-1
 *
 * @author GateKeeper
 * @since T16-1 (2026-09-15)
 */
@Mapper
public interface SysEncryptionConfigMapper extends BaseMapper<SysEncryptionConfig> {
}
