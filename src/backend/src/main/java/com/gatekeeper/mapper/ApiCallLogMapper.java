package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.ApiCallLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 调用日志表 Mapper — 负责 api_call_log 表的数据访问
 */
@Mapper
public interface ApiCallLogMapper extends BaseMapper<ApiCallLog> {
}
