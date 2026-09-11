package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.NotifyChannel;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知渠道表 Mapper — 负责 notify_channel 表的数据访问（T04-C 新建）
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
@Mapper
public interface NotifyChannelMapper extends BaseMapper<NotifyChannel> {
}
