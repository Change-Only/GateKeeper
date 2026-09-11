package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.IpBan;
import org.apache.ibatis.annotations.Mapper;

/**
 * IP 封禁表 Mapper — 负责 ip_ban 表的数据访问
 */
@Mapper
public interface IpBanMapper extends BaseMapper<IpBan> {
}
