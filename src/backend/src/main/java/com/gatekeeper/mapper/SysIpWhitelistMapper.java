package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SysIpWhitelist;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统级访问白名单 Mapper — 负责 sys_ip_whitelist 表的数据访问。
 *
 * <p>T15-4：网关入口全局前置校验的配置面。
 * 表有唯一键 {@code uk_syswl_cidr(ip_cidr)}，同一网段不会重复配置。</p>
 *
 * @author GateKeeper
 * @since T15-4 (2026-09-15)
 */
@Mapper
public interface SysIpWhitelistMapper extends BaseMapper<SysIpWhitelist> {
}
