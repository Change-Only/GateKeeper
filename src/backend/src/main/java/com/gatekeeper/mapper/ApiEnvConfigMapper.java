package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.ApiEnvConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口环境配置表 Mapper — 负责 api_env_config 表的数据访问
 *
 * <p>T03b 接口环境配置：每接口每环境独立上游/Mock/超时，version 为 NULL 表示全版本通用。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Mapper
public interface ApiEnvConfigMapper extends BaseMapper<ApiEnvConfig> {
}
