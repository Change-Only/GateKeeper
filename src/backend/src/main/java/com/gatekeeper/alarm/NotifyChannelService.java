package com.gatekeeper.alarm;

import com.gatekeeper.entity.NotifyChannel;

import java.util.List;

/**
 * 通知渠道服务接口 — T04-C 告警域
 *
 * <p>channelType: WECOM/DINGTALK/EMAIL/SMS/WEBHOOK。channelConfig 以 JSON 字符串存储。</p>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
public interface NotifyChannelService {

    /**
     * 查询通知渠道列表。
     *
     * @param status 状态过滤（可空，1=启用, 0=停用）
     * @return 渠道列表
     */
    List<NotifyChannel> list(Integer status);

    /**
     * 按主键查询渠道详情。
     *
     * @param id 渠道 ID
     * @return 渠道实体
     */
    NotifyChannel get(Long id);

    /**
     * 创建通知渠道。
     *
     * @param channel 入参（channelName/channelType 必填）
     * @return 新建渠道（含自增 ID）
     */
    NotifyChannel create(NotifyChannel channel);

    /**
     * 修改通知渠道。
     *
     * @param id      渠道 ID
     * @param channel 入参（仅非空字段被更新）
     */
    void update(Long id, NotifyChannel channel);

    /**
     * 发送测试消息并记录测试结果（lastTestTime / lastTestResult）。
     *
     * @param id 渠道 ID
     */
    void test(Long id);

    /**
     * 按逗号分隔的 ID 串解析渠道列表（告警评估时由规则 channelIds 解析使用）。
     *
     * @param commaIds 逗号分隔的渠道 ID 串
     * @return 渠道列表（空串/非法返回空列表）
     */
    List<NotifyChannel> listByIds(String commaIds);
}
