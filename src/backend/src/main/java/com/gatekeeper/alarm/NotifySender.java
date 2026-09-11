package com.gatekeeper.alarm;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.gatekeeper.entity.NotifyChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知发送器 — T04-C 告警域
 *
 * <h3>渠道能力</h3>
 * <ul>
 *   <li>WECOM / DINGTALK：真实发送，解析 channelConfig JSON 的 {@code webhook} 字段，
 *       通过 {@link RestTemplate} POST 文本消息。</li>
 *   <li>EMAIL：best-effort，仅记录 SMTP 意图（不真实外发）。</li>
 *   <li>SMS / WEBHOOK：桩实现，仅记录意图（log only）。</li>
 * </ul></p>
 *
 * <p><strong>约束</strong>：所有发送路径均包 try/catch，绝不让异常抛给调用方；返回 boolean 表示本次是否成功。</p>
 *
 * <p>构造：无参构造使用默认 {@link RestTemplate}；测试可注入 mock {@link RestTemplate} 的构造器。</p>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
@Slf4j
@Component
public class NotifySender {

    private final RestTemplate restTemplate;

    /**
     * 默认构造（生产环境 Spring 实例化）。
     */
    public NotifySender() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * 注入指定 RestTemplate 的构造（主要用于单元测试注入 mock）。
     *
     * @param restTemplate RestTemplate 实例
     */
    public NotifySender(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
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
        try {
            switch (type.toUpperCase()) {
                case "WECOM":
                case "DINGTALK":
                    return sendWebhook(channel, title, content);
                case "EMAIL":
                    return sendEmail(channel, title, content);
                case "SMS":
                case "WEBHOOK":
                default:
                    log.info("[stub] channel {} type={} 暂未实发，仅记录意图", channel.getId(), type);
                    return true;
            }
        } catch (Exception e) {
            log.error("notify send failed channelId={} type={}", channel.getId(), type, e);
            return false;
        }
    }

    /**
     * WECOM / DINGTALK 真实 webhook 发送。
     */
    private boolean sendWebhook(NotifyChannel channel, String title, String content) {
        JSONObject cfg = parseConfig(channel.getChannelConfig());
        if (cfg == null) {
            log.warn("channel {} config is empty or invalid JSON, skip", channel.getId());
            return false;
        }
        String webhook = cfg.getStr("webhook", cfg.getStr("url"));
        if (!StringUtils.hasText(webhook)) {
            log.warn("channel {} missing webhook url, skip", channel.getId());
            return false;
        }
        if (channel.getStatus() != null && channel.getStatus() == 0) {
            log.warn("channel {} is disabled, skip send", channel.getId());
            return true;
        }
        Map<String, Object> body = buildTextBody(title, content);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        String resp = restTemplate.postForObject(webhook, entity, String.class);
        log.info("channel {} webhook sent, response: {}", channel.getId(), resp);
        return true;
    }

    /**
     * EMAIL best-effort：仅记录 SMTP 意图，不真实外发。
     */
    private boolean sendEmail(NotifyChannel channel, String title, String content) {
        JSONObject cfg = parseConfig(channel.getChannelConfig());
        String smtp = cfg == null ? "n/a" : cfg.getStr("smtpHost", cfg.getStr("host", "n/a"));
        log.info("[email-best-effort] would send via SMTP({}) title={} content={}",
                smtp, title, content);
        return true;
    }

    /**
     * 解析渠道配置 JSON（容错：非法 JSON 返回 null）。
     */
    private JSONObject parseConfig(String config) {
        if (!StringUtils.hasText(config)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(config);
        } catch (Exception e) {
            log.warn("parse channelConfig failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构造企业微信/钉钉文本消息体。
     */
    private Map<String, Object> buildTextBody(String title, String content) {
        Map<String, Object> text = new HashMap<>(2);
        text.put("content", title + "\n" + content);
        Map<String, Object> body = new HashMap<>(2);
        body.put("msgtype", "text");
        body.put("text", text);
        return body;
    }
}
