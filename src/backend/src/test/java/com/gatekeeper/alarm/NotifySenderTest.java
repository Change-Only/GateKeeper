package com.gatekeeper.alarm;

import com.gatekeeper.entity.NotifyChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    @DisplayName("WEBHOOK 为桩分支，不发起 POST")
    void webhookIsStubNoPost() {
        NotifyChannel c = channel("WEBHOOK", "{\"webhook\":\"https://example.com/x\"}", 1);
        boolean ok = sender.send(c, "t", "c");
        assertTrue(ok);
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
}
