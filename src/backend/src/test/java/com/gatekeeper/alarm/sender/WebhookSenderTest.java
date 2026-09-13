package com.gatekeeper.alarm.sender;

import com.gatekeeper.entity.NotifyChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WebhookSender 单测 — T09 方案 §5 T3/T4 + R4（停用语义）
 *
 * <p>T3（errcode≠0 判失败）在 {@code NotifySenderTest} 已有同型用例并完成了
 * 「施工前红 / 施工后绿」取证；此处为适配器直调层的等价覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WebhookSender T09-N1")
class WebhookSenderTest {

    @Mock
    private RestTemplate restTemplate;

    private WebhookSender sender;

    @BeforeEach
    void setUp() {
        sender = new WebhookSender(restTemplate);
    }

    private NotifyChannel channel(String config, Integer status) {
        NotifyChannel c = new NotifyChannel();
        c.setId(1L);
        c.setChannelType("WECOM");
        c.setChannelConfig(config);
        c.setStatus(status);
        return c;
    }

    @Test
    @DisplayName("T4：config 为 NULL → false，不发起 POST")
    void nullConfigFails() {
        assertFalse(sender.send(channel(null, 1), "t", "c"));
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    @DisplayName("T4：config 非法 JSON → false")
    void invalidJsonConfigFails() {
        assertFalse(sender.send(channel("not-a-json", 1), "t", "c"));
    }

    @Test
    @DisplayName("T4：webhook URL 缺失 → false，不发起 POST")
    void missingWebhookUrlFails() {
        assertFalse(sender.send(channel("{\"other\":\"x\"}", 1), "t", "c"));
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    @DisplayName("R4：停用渠道（status=0）→ false（语义=未发送），不发起 POST")
    void disabledChannelReturnsFalse() {
        assertFalse(sender.send(channel("{\"webhook\":\"https://example.com/hook\"}", 0), "t", "c"),
                "停用渠道旧实现返回 true 系语义缺陷（方案 R4），应返回 false");
        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    @Test
    @DisplayName("T3：HTTP 200 + {\"errcode\":93000} → false")
    void errcodeNonZeroFails() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errcode\":93000,\"errmsg\":\"invalid webhook\"}");
        assertFalse(sender.send(channel("{\"webhook\":\"https://example.com/hook\"}", 1), "t", "c"));
    }

    @Test
    @DisplayName("T3：HTTP 200 + {\"errcode\":0} → true")
    void errcodeZeroSucceeds() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"errcode\":0,\"errmsg\":\"ok\"}");
        assertTrue(sender.send(channel("{\"webhook\":\"https://example.com/hook\"}", 1), "t", "c"));
    }

    @Test
    @DisplayName("resp 为 null（HTTP 2xx 无 body）→ 维持成功（与旧行为一致）")
    void nullRespSucceeds() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(null);
        assertTrue(sender.send(channel("{\"webhook\":\"https://example.com/hook\"}", 1), "t", "c"));
    }

    @Test
    @DisplayName("发送异常被捕获，返回 false 不外抛")
    void exceptionReturnsFalse() {
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("boom"));
        assertFalse(sender.send(channel("{\"webhook\":\"https://example.com/hook\"}", 1), "t", "c"));
    }
}
