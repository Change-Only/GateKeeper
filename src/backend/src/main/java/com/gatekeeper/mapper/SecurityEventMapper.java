package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SecurityEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 安全事件表 Mapper — 负责 security_event 表的数据访问
 */
@Mapper
public interface SecurityEventMapper extends BaseMapper<SecurityEvent> {
}
