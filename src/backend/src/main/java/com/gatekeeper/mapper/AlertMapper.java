package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.Alert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警表 Mapper — 负责 alert 表的数据访问
 */
@Mapper
public interface AlertMapper extends BaseMapper<Alert> {
}
