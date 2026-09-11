package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.ExportTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 导出任务 Mapper — 提供 export_task 表的基础增删改查能力
 */
@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTask> {
}
