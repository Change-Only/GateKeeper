package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.AppApiGrant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 应用接口授权表 Mapper — 负责 app_api_grant 表的数据访问（T04-A 新建）
 *
 * <p>app_api_grant 替代存量 app_api_permission（无审批、无有效期、无环境），
 * 由 {@link com.gatekeeper.grant.GrantStateMachine} 约束状态流转。</p>
 */
@Mapper
public interface AppApiGrantMapper extends BaseMapper<AppApiGrant> {
}
