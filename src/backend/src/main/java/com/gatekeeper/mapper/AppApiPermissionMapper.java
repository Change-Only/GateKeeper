package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.AppApiPermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 应用接口权限表 Mapper — 负责 app_api_permission 表的数据访问
 */
@Mapper
public interface AppApiPermissionMapper extends BaseMapper<AppApiPermission> {
}
