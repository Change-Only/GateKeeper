package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SysInterfaceCryptoConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口信息加密开关 Mapper（单行表，恒取 id=1）— T17
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
@Mapper
public interface SysInterfaceCryptoConfigMapper extends BaseMapper<SysInterfaceCryptoConfig> {
}
