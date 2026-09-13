package com.gatekeeper.alarm.sender;

import com.gatekeeper.entity.NotifyChannel;

/**
 * 渠道发送适配器 SPI — T09 告警通知渠道扩展
 *
 * <p>每类型一个 {@code @Component} 实现（组件式策略，对齐 {@code com.gatekeeper.security}
 * 包 5 个 Detector 的风格：独立组件、构造注入依赖、公共入口方法、整体 try/catch fail-open）。
 * {@code NotifySender} 注入 {@code List<ChannelSender>} 按渠道 type 路由。</p>
 *
 * <p><strong>铁律</strong>：实现方 {@link #send} <strong>永不抛异常</strong>（内部全部 catch），
 * 只以 boolean 表达本次发送是否成功 —— 告警发送失败绝不能阻断评估主流程。</p>
 *
 * @author GateKeeper
 * @since T09-N1 (APIM V2)
 */
public interface ChannelSender {

    /**
     * 本适配器支持的渠道类型（大写，与 {@code notify_channel.channel_type} 对应）。
     * 如 {"WECOM","DINGTALK"} / {"EMAIL"}。
     */
    String[] supportTypes();

    /**
     * 发送一条通知。
     *
     * @param channel 渠道（含类型与配置；channelConfig 中的敏感字段已由服务层解密）
     * @param title   标题（邮件主题 / IM 消息首行）
     * @param content 正文
     * @return 本次发送是否成功（永不抛异常）
     */
    boolean send(NotifyChannel channel, String title, String content);
}
