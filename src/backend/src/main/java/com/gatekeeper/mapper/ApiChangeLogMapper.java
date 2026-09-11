package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.ApiChangeLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口变更历史表 Mapper — 负责 api_change_log 表的数据访问
 *
 * <p>T03b 接口变更历史：追加型（append-only），记录接口生命周期变更轨迹。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Mapper
public interface ApiChangeLogMapper extends BaseMapper<ApiChangeLog> {
}
