package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.NotifyChannel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 通知渠道表 Mapper — 负责 notify_channel 表的数据访问（T04-C 新建）
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
@Mapper
public interface NotifyChannelMapper extends BaseMapper<NotifyChannel> {

    /**
     * 统计引用了指定通知渠道的告警规则条数（T07-A）。
     *
     * <p>{@code alarm_rule.channel_ids} 是逗号分隔的渠道 id 字符串（如 {@code '1,3'}），
     * 且 notify_channel 与 alarm_rule 之间**没有外键约束**，因此必须在应用层做引用检查，
     * 否则物理删除渠道会在告警规则里留下悬空 id —— 规则 UI 上看起来配置完好，
     * 但告警触发时解析不到渠道，形成「你以为会告警、实际不会」的静默失效。</p>
     *
     * <p>匹配必须用 {@code FIND_IN_SET}：<strong>不能</strong>用 {@code LIKE '%1%'}，
     * 否则 '{@code 11}'、'{@code 21}' 会被误判为包含 id=1。</p>
     *
     * @param channelId 渠道 id
     * @return 引用该渠道的告警规则条数
     */
    @Select("SELECT COUNT(*) FROM alarm_rule WHERE FIND_IN_SET(#{channelId}, channel_ids)")
    long countRulesUsingChannel(@Param("channelId") Long channelId);
}
