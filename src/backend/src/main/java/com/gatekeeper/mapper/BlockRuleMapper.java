package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.BlockRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 动态封禁规则 Mapper — 负责 block_rule 表的数据访问
 *
 * <p>T04-D 风控「封禁」规则：与存量 security_rule（检测）职责分离，block_rule 命中后
 * 由 {@code BlockExecutor} 调用 {@code BanExecutor} 写入 ip_ban / 封禁名单。</p>
 *
 * @author GateKeeper
 * @since T04-D (APIM V2)
 */
@Mapper
public interface BlockRuleMapper extends BaseMapper<BlockRule> {
}
