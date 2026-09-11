package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.AppRateLimit;
import org.apache.ibatis.annotations.Mapper;

/**
 * 限流配置表 Mapper — 负责 app_rate_limit 表的数据访问
 */
@Mapper
public interface AppRateLimitMapper extends BaseMapper<AppRateLimit> {
}
