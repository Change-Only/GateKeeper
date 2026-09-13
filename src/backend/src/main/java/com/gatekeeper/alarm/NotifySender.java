package com.gatekeeper.alarm;

import com.gatekeeper.alarm.sender.ChannelSender;
import com.gatekeeper.alarm.sender.WebhookSender;
import com.gatekeeper.entity.NotifyChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

/**
 * 通知发送器 — T04-C 告警域；T09-N1 改造为按渠道路由的编排器
 *
 * <p><strong>类名与 {@link #send(NotifyChannel, String, String)} 签名保持不变</strong>，
 * 调用方（AlarmRuleServiceImpl / NotifyChannelServiceImpl）零改动。内部改为注入
 * {@code List<ChannelSender>}（{@code com.gatekeeper.alarm.sender} 包的组件式策略，
 * 对齐 security/*Detector 风格）按渠道 type 路由：</p>
 * <ul>
 *   <li>WECOM / DINGTALK / WEBHOOK → {@link WebhookSender}（errcode≠0 判失败，方案 R5）</li>
 *   <li>EMAIL → EmailSmtpSender（SMTP 真发，方案 §3.2）</li>
 *   <li>SMS / 未知类型 → <strong>退化为现状桩发</strong>：log 意图返回 true
 *       （T08 兼容 —— 存量行为不变，待后续批次实装）。</li>
 * </ul>
 *
 * <p><strong>约束</strong>：所有发送路径均包 try/catch，绝不让异常抛给调用方；
 * 返回 boolean 表示本次是否成功。</p>
 *
 * <p>构造：生产环境 Spring 注入 {@code List<ChannelSender>}；测试可用
 * {@link #NotifySender(RestTemplate)} 注入 mock RestTemplate（内部包一个 WebhookSender，
 * 既有 NotifySenderTest 范式不破坏）。</p>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)，T09-N1 改造
 */
@Slf4j
@Component
public class NotifySender {

    private final List<ChannelSender> senders;

    /**
     * 生产构造：Spring 注入全部 {@link ChannelSender} 实现，按渠道路由。
     *
     * @param senders 适配器列表（可为空 —— 全部走桩发退化）
     */
    @Autowired
    public NotifySender(List<ChannelSender> senders) {
        this.senders = senders == null ? Collections.emptyList() : senders;
    }

    /**
     * 测试构造：注入 mock {@link RestTemplate}（既有 NotifySenderTest 范式）。
     *
     * <p>内部包装一个基于该 RestTemplate 的 {@link WebhookSender}，因此 WECOM/DINGTALK
     * 分支可被 mock 验证；其余类型（EMAIL/SMS/...）无匹配适配器，走桩发退化返回 true。</p>
     *
     * @param restTemplate RestTemplate 实例（通常为 mock）
     */
    public NotifySender(RestTemplate restTemplate) {
        this(Collections.singletonList(new WebhookSender(restTemplate)));
    }

    /**
     * 发送通知。
     *
     * @param channel 通知渠道（含类型与配置）
     * @param title   告警标题
     * @param content 告警内容
     * @return 本次发送是否成功（失败/桩发均不抛异常）
     */
    public boolean send(NotifyChannel channel, String title, String content) {
        if (channel == null) {
            log.warn("notify channel is null, skip send");
            return false;
        }
        String type = channel.getChannelType();
        if (!StringUtils.hasText(type)) {
            log.warn("channel {} type is empty, skip send", channel.getId());
            return false;
        }
        String upper = type.trim().toUpperCase();
        try {
            for (ChannelSender sender : senders) {
                for (String supported : sender.supportTypes()) {
                    if (upper.equals(supported.trim().toUpperCase())) {
                        return sender.send(channel, title, content);
                    }
                }
            }
            // 无匹配实现（SMS / 未知类型）：退化为现状桩发（T08 兼容）
            // 注：WEBHOOK 已由 WebhookSender.supportTypes() 覆盖，走真发路径，不再落到此处
            log.info("[stub] channel {} type={} 暂未实发，仅记录意图", channel.getId(), type);
            return true;
        } catch (Exception e) {
            log.error("notify send failed channelId={} type={}", channel.getId(), type, e);
            return false;
        }
    }
}
