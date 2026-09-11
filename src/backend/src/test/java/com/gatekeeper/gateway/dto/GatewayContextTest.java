package com.gatekeeper.gateway.dto;

import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 网关上下文构建测试
 * 重点验证客户端 IP 获取策略：默认不信任 X-Forwarded-For（防伪造），
 * 仅当 trustXff=true（部署在可信代理之后）才解析代理头。
 */
class GatewayContextTest {

    /** 默认不信任 XFF：即使携带伪造代理头也应取直连地址 */
    @Test
    void shouldUseRemoteAddrWhenXffNotTrusted() {
        HttpServletRequest request = mockRequest("10.0.0.1", "1.2.3.4, 9.9.9.9");

        GatewayContext ctx = GatewayContext.from(request);

        assertEquals("10.0.0.1", ctx.getClientIp(), "未信任 XFF 时应使用 RemoteAddr");
    }

    /** 单参数重载同样默认不信任 XFF */
    @Test
    void shouldNotTrustXffByDefaultWithSingleArgOverload() {
        HttpServletRequest request = mockRequest("10.0.0.1", "9.9.9.9");

        GatewayContext ctx = GatewayContext.from(request);

        assertEquals("10.0.0.1", ctx.getClientIp());
    }

    /** 信任 XFF 时应取最后一跳（最接近真实来源） */
    @Test
    void shouldUseLastXffHopWhenTrusted() {
        HttpServletRequest request = mockRequest("10.0.0.1", "1.2.3.4, 9.9.9.9");

        GatewayContext ctx = GatewayContext.from(request, true);

        assertEquals("9.9.9.9", ctx.getClientIp());
    }

    /** 信任 XFF 但无代理头时回退 RemoteAddr */
    @Test
    void shouldFallbackToRemoteAddrWhenTrustedButNoHeader() {
        HttpServletRequest request = mockRequest("10.0.0.1", null);

        GatewayContext ctx = GatewayContext.from(request, true);

        assertEquals("10.0.0.1", ctx.getClientIp());
    }

    /** 应正确解析认证请求头与基础请求信息 */
    @Test
    void shouldParseRequestHeaders() {
        HttpServletRequest request = mockRequest("10.0.0.1", null);
        when(request.getRequestURI()).thenReturn("/gateway/order/create");
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("X-App-Key")).thenReturn("ak_001");
        when(request.getHeader("X-Signature")).thenReturn("signature-value");
        when(request.getHeader("X-Timestamp")).thenReturn("1756000000000");
        when(request.getHeader("X-Nonce")).thenReturn("nonce-001");

        GatewayContext ctx = GatewayContext.from(request);

        assertEquals("/gateway/order/create", ctx.getPath());
        assertEquals("POST", ctx.getMethod());
        assertEquals("ak_001", ctx.getAppKey());
        assertEquals("signature-value", ctx.getSignature());
        assertEquals(1756000000000L, ctx.getTimestamp());
        assertEquals("nonce-001", ctx.getNonce());
        // 初始状态应为未限流/未拦截/未认证
        assertTrue(!ctx.isRateLimited() && !ctx.isBlocked() && !ctx.isAuthSuccess());
        assertNull(ctx.getBlockReason());
    }

    private HttpServletRequest mockRequest(String remoteAddr, String xff) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Forwarded-For")).thenReturn(xff);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        return request;
    }
}
