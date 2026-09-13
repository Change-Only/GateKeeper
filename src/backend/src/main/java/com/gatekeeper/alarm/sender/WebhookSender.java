package com.gatekeeper.alarm.sender;

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
 * 企业微信 / 钉钉机器人 webhook 发送器 — T09-N1 迁自 {@code NotifySender.sendWebhook}
 *
 * <p>相对旧实现的两个语义修正（方案 §6 R4/R5）：</p>
 * <ul>
 *   <li><strong>errcode 判定（R5）</strong>：企微/钉钉对无效 webhook 等错误返回的是
 *       <strong>HTTP 200 + body {@code {"errcode":非0,"errmsg":...}}</strong>，旧实现只看
 *       HTTP 不抛异常即 return true，把「invalid webhook」也记为成功。现解析 body，
 *       {@code errcode != 0} 一律判失败。</li>
 *   <li><strong>停用渠道（R4）</strong>：status=0 时旧实现跳过发送但返回 true（语义=已发送成功，
 *       会误导 lastTestResult 为 SUCCESS）。现改为返回 false + log（语义=未发送）。</li>
 * </ul>
 *
 * <p>resp 为 null / 非 JSON（HTTP 2xx 但无判定体）时维持成功 —— 与旧行为一致，不凭空制造失败。</p>
 *
 * @author GateKeeper
 * @since T09-N1 (APIM V2)
 */
@Slf4j
@Component
public class WebhookSender implements ChannelSender {

    private final RestTemplate restTemplate;

    /**
     * 默认构造（生产环境 Spring 实例化）。
     */
    public WebhookSender() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * 注入指定 RestTemplate 的构造（主要用于单元测试注入 mock）。
     *
     * @param restTemplate RestTemplate 实例
     */
    public WebhookSender(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public String[] supportTypes() {
        // WEBHOOK（通用 webhook）由 lead 于 N1 验收时裁定接入（T10 收口）：
        // 前端已为 WEBHOOK 渠道提供 {webhook } 配置入口（SysNotify CONFIG_SCHEMA），
        // 若后端不接则该渠道配置后仍走桩发返回 true——构成「假成功」，正是本项目要消灭的缺陷类。
        // 通用 webhook 响应无 errcode 结构时 isErrcodeOk 判成功（非 JSON 2xx 维持成功），语义安全。
        return new String[]{"WECOM", "DINGTALK", "WEBHOOK"};
    }

    @Override
    public boolean send(NotifyChannel channel, String title, String content) {
        if (channel == null) {
            return false;
        }
        try {
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
            // R4：停用渠道 = 未发送，返回 false（旧实现返回 true 系语义缺陷）
            if (channel.getStatus() != null && channel.getStatus() == 0) {
                log.warn("channel {} is disabled, skip send", channel.getId());
                return false;
            }
            Map<String, Object> body = buildTextBody(title, content);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String resp = restTemplate.postForObject(webhook, entity, String.class);
            log.info("channel {} webhook sent, response: {}", channel.getId(), resp);
            // R5：企微/钉钉错误以 HTTP 200 + errcode!=0 返回，必须判失败
            return isErrcodeOk(resp);
        } catch (Exception e) {
            log.error("channel {} webhook send failed: {}", channel.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 解析响应 body 的 errcode（企微/钉钉同一约定：0=成功）。
     *
     * <p>resp 为空 / 非 JSON（HTTP 2xx 但无可判定体）→ 维持成功（与旧行为一致）。</p>
     */
    private boolean isErrcodeOk(String resp) {
        if (!StringUtils.hasText(resp)) {
            return true;
        }
        try {
            JSONObject json = JSONUtil.parseObj(resp);
            Integer errcode = json.getInt("errcode");
            if (errcode != null && errcode != 0) {
                log.warn("webhook rejected: errcode={}, errmsg={}", errcode, json.getStr("errmsg"));
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("webhook resp is not JSON, treat as success: {}", resp);
            return true;
        }
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
        text.put("content", (title == null ? "" : title) + "\n" + (content == null ? "" : content));
        Map<String, Object> body = new HashMap<>(2);
        body.put("msgtype", "text");
        body.put("text", text);
        return body;
    }
}
