package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.AlarmRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警规则表 Mapper — 负责 alarm_rule 表的数据访问（T04-C 新建）
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
@Mapper
public interface AlarmRuleMapper extends BaseMapper<AlarmRule> {
}
