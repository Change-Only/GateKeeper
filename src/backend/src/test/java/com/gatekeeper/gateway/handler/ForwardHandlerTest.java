package com.gatekeeper.gateway.handler;

import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.EffectiveEnvConfig;
import com.gatekeeper.gateway.dto.GatewayContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ForwardHandler 单元测试 — T13「Mock 真正生效」的核心回归防线
 *
 * <p>用户反馈第 1 条 bug：「接口列表环境配置中开启 Mock 不起作用」。
 * 根因是改造前 ForwardHandler 只用 {@code api_interface.backend_url} 转发，
 * 环境配置表里的 {@code mock_enabled} <b>全仓没有任何消费点</b>（只写不用）。
 * 因此这里必须钉死两件事：
 * <ol>
 *   <li>mockEnabled=1 时**不发起任何网络请求**，直接返回配置的状态码与报文；</li>
 *   <li>mockEnabled=0 时必须走真实转发（用不可达地址验证它确实去连了，而不是也返回 Mock）。</li>
 * </ol></p>
 */
@DisplayName("ForwardHandler：Mock 短路 / 未开启时真实转发")
class ForwardHandlerTest {

    /** 连接池参数与生产解耦，测试只关心行为 */
    private ForwardHandler newHandler() {
        return new ForwardHandler(4, 2);
    }

    @Test
    @DisplayName("mockEnabled=1 → 短路返回配置的状态码与报文，不发请求")
    void mockShortCircuits() {
        ForwardHandler handler = newHandler();
        GatewayContext ctx = ctx("/test", "GET");
        EffectiveEnvConfig eff = new EffectiveEnvConfig();
        eff.setEnvCode("prod");
        eff.setMockEnabled(1);
        eff.setMockStatus(201);
        eff.setMockResponse("{\"hello\":\"mock\"}");
        eff.setSourceType(EffectiveEnvConfig.SOURCE_GROUP);
        eff.setSourcePath("配网 / 核心指标");
        ctx.setEffectiveEnvConfig(eff);
        // 故意给一个不可达地址：若代码没有短路，这里会抛 502，测试即失败
        ctx.setBackendUrl("http://127.0.0.1:1/should-not-be-called");

        handler.handle(ctx);

        assertEquals(Integer.valueOf(201), ctx.getResponseStatus());
        assertEquals("{\"hello\":\"mock\"}", ctx.getResponseBody(), "配置了 mockResponse 就原样返回");
        assertTrue(ctx.isMockResponse(), "必须标记 Mock 响应，否则 GatewayController 无法把配置的状态码透传给调用方");
    }

    @Test
    @DisplayName("mockEnabled=1 但未配 mockResponse → 返回自解释的默认提示 JSON（含 mock/interface/env）")
    void mockDefaultBodyIsSelfExplaining() {
        ForwardHandler handler = newHandler();
        GatewayContext ctx = ctx("/order/create", "POST");
        EffectiveEnvConfig eff = new EffectiveEnvConfig();
        eff.setEnvCode("dev");
        eff.setMockEnabled(1);
        eff.setMockStatus(null); // 未配状态码 → 兜底 200
        eff.setSourceType(EffectiveEnvConfig.SOURCE_GROUP);
        ctx.setEffectiveEnvConfig(eff);

        handler.handle(ctx);

        assertEquals(Integer.valueOf(200), ctx.getResponseStatus(), "Mock 状态码缺省必须是 200，不能是 null");
        String body = ctx.getResponseBody();
        assertNotNull(body);
        assertTrue(body.contains("\"mock\":true"), "默认报文必须自解释，避免被误当真实业务数据");
        assertTrue(body.contains("/order/create"), "默认报文要带上接口路径，便于排障");
        assertTrue(body.contains("\"env\":\"dev\""));
    }

    @Test
    @DisplayName("mockEnabled=0 → 不做 Mock 短路（用不可达地址验证它确实尝试了转发）")
    void mockOffStillForwards() {
        ForwardHandler handler = newHandler();
        GatewayContext ctx = ctx("/test", "GET");
        EffectiveEnvConfig eff = new EffectiveEnvConfig();
        eff.setEnvCode("prod");
        eff.setMockEnabled(0);
        eff.setConnectTimeout(300);
        eff.setReadTimeout(300);
        ctx.setEffectiveEnvConfig(eff);
        ctx.setBackendUrl("http://127.0.0.1:1/nope");

        GatewayException ex = assertThrows(GatewayException.class, () -> handler.handle(ctx));
        assertEquals(502, ex.getCode(), "连不上上游应是 502，而不是把 Mock 报文当成功返回");
        assertNull(ctx.getResponseBody());
        assertTrue(!ctx.isMockResponse(), "未命中 Mock 时不能打上 Mock 标记");
    }

    @Test
    @DisplayName("上下文没有生效配置且解析器不可用 → 保持改造前行为（用接口 backendUrl 转发），不 NPE")
    void noEffectiveConfigFallsBackGracefully() {
        ForwardHandler handler = newHandler();
        GatewayContext ctx = ctx("/test", "GET");
        ctx.setEffectiveEnvConfig(null);
        ctx.setBackendUrl("http://127.0.0.1:1/nope");

        // 解析器为 null（未注入）时不能抛 NPE，而是照旧转发 → 502
        GatewayException ex = assertThrows(GatewayException.class, () -> handler.handle(ctx));
        assertEquals(502, ex.getCode());
    }

    private GatewayContext ctx(String path, String method) {
        GatewayContext ctx = new GatewayContext();
        ctx.setPath(path);
        ctx.setInterfacePath(path);
        ctx.setMethod(method);
        ctx.setEnvCode("prod");
        return ctx;
    }
}
