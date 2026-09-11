package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.AppIpWhitelist;
import org.apache.ibatis.annotations.Mapper;

/**
 * IP 白名单表 Mapper — 负责 app_ip_whitelist 表的数据访问
 */
@Mapper
public interface AppIpWhitelistMapper extends BaseMapper<AppIpWhitelist> {
}
