package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.ApiGroupEnvConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口分组环境配置表 Mapper — 负责 api_group_env_config 表的数据访问
 *
 * <p>T13：分组维度环境配置（每分组每环境一条，唯一键 group_id+env_code），
 * 网关侧由 {@code EnvConfigResolver} 沿分组树向上继承解析。</p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@Mapper
public interface ApiGroupEnvConfigMapper extends BaseMapper<ApiGroupEnvConfig> {
}
