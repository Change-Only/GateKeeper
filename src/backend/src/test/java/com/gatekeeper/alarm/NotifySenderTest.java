package com.gatekeeper.alarm;

import com.gatekeeper.alarm.sender.HttpApiSender;
import com.gatekeeper.entity.NotifyChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotifySender 单测 — mock RestTemplate 验证渠道分支
 *
 * <ul>
 *   <li>WECOM / DINGTALK：真实发起 postForObject</li>
 *   <li>SMS：桩分支，不发起 postForObject</li>
 *   <li>发送异常：捕获且不对外抛，返回 false</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotifySender 渠道分支")
class NotifySenderTest {

    @Mock
    private RestTemplate restTemplate;

    private NotifySender sender;

    @BeforeEach
    void setUp() {
        sender = new NotifySender(restTemplate);
    }

    private NotifyChannel channel(String type, String config, Integer status) {
        NotifyChannel c = new NotifyChannel();
        c.setId(1L);
        c.setChannelType(type);
        c.setChannelConfig(config);
        c.setStatus(status);
        return c;
    }

    @Test
    @DisplayName("WECOM 通过 webhook 真实发起 POST")
    void wecomAttemptsPost() {
        NotifyChannel c = channel("WECOM", "{\"webhook\":\"https://example.com/hook\"}", 1);
        boolean ok = sender.send(c, "title", "content");
        assertTrue(ok);
        verify(restTemplate).postForObject(eq("https://example.com/hook"),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("DINGTALK 通过 webhook 真实发起 POST")
    void dingtalkAttemptsPost() {
        NotifyChannel c = channel("DINGTALK", "{\"webhook\":\"https://example.com/dd\"}", 1);
        boolean ok = sender.send(c, "t", "c");
        assertTrue(ok);
        verify(restTemplate).postForObject(anyString(), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("SMS 为桩分支，不发起 POST")
    void smsIsStubNoPost() {
        NotifyChannel c = channel("SMS", "{}", 1);
        boolean ok = sender.send(c, "t", "c");
        assertTrue(ok);
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    @DisplayName("WEBHOOK（通用）已接入 WebhookSender 真发：发起 POST 且非 JSON 2xx 响应判成功")
    void webhookTypeActuallySends() {
        // lead 裁定（T10 收口）：WEBHOOK 纳入 WebhookSender.supportTypes——
        // 前端已提供 {webhook} 配置入口，后端桩发返回 true 会构成「假成功」。
        // 通用 webhook 响应通常无 errcode 结构（如纯文本 "ok"），isErrcodeOk 对
        // 非 JSON 2xx 维持成功 —— 本用例锚定该语义。
        NotifyChannel c = channel("WEBHOOK", "{\"webhook\":\"https://example.com/x\"}", 1);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("ok");
        boolean ok = sender.send(c, "t", "c");
        assertTrue(ok);
        verify(restTemplate, atLeastOnce()).postForObject(anyString(), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("WEBHOOK 停用渠道返回 false（不发起 POST）")
    void webhookTypeDisabledSkips() {
        NotifyChannel c = channel("WEBHOOK", "{\"webhook\":\"https://example.com/x\"}", 0);
        assertFalse(sender.send(c, "t", "c"));
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    @DisplayName("发送异常被捕获且不对外抛，返回 false")
    void failureDoesNotThrow() {
        NotifyChannel c = channel("WECOM", "{\"webhook\":\"https://example.com/hook\"}", 1);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("boom"));
        assertDoesNotThrow(() -> sender.send(c, "t", "c"));
        assertFalse(sender.send(c, "t", "c"));
    }

    /**
     * T09 方案 §5-T3（errcode 语义缺陷的「能失败用例」锚点）：
     * 企微/钉钉 webhook 返回 HTTP 200 + body errcode≠0 时，必须判为失败。
     * 现状（T09 施工前）：sendWebhook 只要 HTTP 不抛异常就 return true，
     * 即「invalid webhook」也被记为发送成功 —— 本用例施工前必红，施工后转绿。
     */
    @Test
    @DisplayName("T3：WECOM 返回 HTTP 200 + {\"errcode\":93000} 应判失败（施工前必红）")
    void wecomErrcodeNonZeroFails() {
        NotifyChannel c = channel("WECOM", "{\"webhook\":\"https://example.com/hook\"}", 1);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errcode\":93000,\"errmsg\":\"invalid webhook\"}");
        assertFalse(sender.send(c, "t", "c"),
                "errcode=93000 表示企微拒绝，却返回了成功 —— 语义缺陷（方案 R5）");
    }

    /**
     * T09 方案 §5-T3 对照：errcode=0 才是成功。
     */
    @Test
    @DisplayName("T3 对照：errcode=0 判成功")
    void wecomErrcodeZeroSucceeds() {
        NotifyChannel c = channel("WECOM", "{\"webhook\":\"https://example.com/hook\"}", 1);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errcode\":0,\"errmsg\":\"ok\"}");
        assertTrue(sender.send(c, "t", "c"));
    }

    /**
     * T09 方案 §5-T8：无匹配 ChannelSender 的渠道类型（SMS/WEBHOOK/未知新类型）
     * 退化为现状桩发 —— log 意图返回 true（存量行为不变，待后续批次实装）。
     */
    @Test
    @DisplayName("T8：未知渠道类型无匹配 sender → 桩发返回 true，不发起 POST")
    void unknownTypeFallsBackToStub() {
        NotifyChannel c = channel("FUTURE_CHANNEL_TYPE", "{}", 1);
        assertTrue(sender.send(c, "t", "c"));
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    /**
     * T12：新增渠道类型「HTTP（自定义接口）」必须由 HttpApiSender 真发，**不得落回桩发**。
     *
     * <p>桩发分支恒返回 true ⇒ 若 supportTypes 漏注册，界面上「测试」会显示成功而实际未发出 ——
     * 即本项目明确要消灭的「配置了却不发」假成功。本用例以 verify(exchange) 锚定真发路径。</p>
     */
    @Test
    @DisplayName("T12：HTTP 渠道由 HttpApiSender 真发，不落回桩发")
    void httpTypeActuallySends() {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));
        // 单测构造（注入 mock RestTemplate 的 HttpApiSender）；生产由 Spring 注入全部 ChannelSender
        NotifySender routed = new NotifySender(Collections.singletonList(new HttpApiSender(restTemplate)));
        NotifyChannel c = channel("HTTP", "{\"url\":\"https://example.com/api\"}", 1);
        assertTrue(routed.send(c, "t", "c"));
        verify(restTemplate).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    /**
     * T09 方案 §5-T8：channel 为 null → 直接 false（不抛异常）。
     */
    @Test
    @DisplayName("T8 边界：channel 为 null → false 不抛")
    void nullChannelReturnsFalse() {
        assertFalse(sender.send(null, "t", "c"));
    }
}
