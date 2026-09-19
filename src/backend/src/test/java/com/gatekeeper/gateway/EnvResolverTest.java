package com.gatekeeper.gateway;

import com.gatekeeper.gateway.dto.GatewayContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 环境码解析器测试 —— T19 修复「X-Gk-Env 永不生效」的回归防线。
 *
 * <p>修复前的实际行为：{@code AppAuthHandler}（@Order 1）自己读 {@code X-Env} 并先写死
 * {@code ctx.envCode}，{@code VersionRouteHandler}（@Order 6）再用「已存在则不覆盖」写入
 * {@code X-Gk-Env} 的结果 —— 于是 {@code X-Gk-Env} 永远没有机会生效。
 * 现在两个头统一在本类里按明确优先级解析，这组用例把顺序钉死。</p>
 */
@DisplayName("EnvResolver：环境头优先级 X-Gk-Env > X-Env > gatekeeper.env > prod")
class EnvResolverTest {

    private EnvResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new EnvResolver();
    }

    /** 反射设置默认环境（模拟 Spring @Value 注入 gatekeeper.env） */
    private void setDefaultEnv(String value) throws Exception {
        Field field = EnvResolver.class.getDeclaredField("defaultEnv");
        field.setAccessible(true);
        field.set(resolver, value);
    }

    /** 构造带请求头的上下文 */
    private GatewayContext ctxWithHeaders(String gkEnv, String legacyEnv) {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.lenient().when(request.getHeader(EnvResolver.HEADER_ENV_PREFERRED)).thenReturn(gkEnv);
        Mockito.lenient().when(request.getHeader(EnvResolver.HEADER_ENV_LEGACY)).thenReturn(legacyEnv);
        GatewayContext ctx = new GatewayContext();
        ctx.setHttpRequest(request);
        return ctx;
    }

    @Test
    @DisplayName("两个头都在 → X-Gk-Env 优先（修复点本身）")
    void preferredHeaderWinsOverLegacy() {
        assertEquals("gray", resolver.resolve(ctxWithHeaders("gray", "test")));
    }

    @Test
    @DisplayName("只有 X-Env → 仍生效（对早期接入方零回归）")
    void legacyHeaderStillWorks() {
        assertEquals("test", resolver.resolve(ctxWithHeaders(null, "test")));
    }

    @Test
    @DisplayName("X-Gk-Env 为空白串 → 视为未提供，回落到 X-Env")
    void blankPreferredFallsBackToLegacy() {
        assertEquals("test", resolver.resolve(ctxWithHeaders("   ", "test")));
    }

    @Test
    @DisplayName("两个头都没有 → 回退 gatekeeper.env")
    void fallsBackToConfiguredDefault() throws Exception {
        setDefaultEnv("pre");

        assertEquals("pre", resolver.resolve(ctxWithHeaders(null, null)));
    }

    @Test
    @DisplayName("配置也没配（字段默认值）→ 归一为 prod")
    void defaultsToProd() {
        assertEquals("prod", resolver.resolve(ctxWithHeaders(null, null)));
    }

    @Test
    @DisplayName("上下文 / 请求为空 → 不抛异常，回退 prod")
    void toleratesNullContext() throws Exception {
        assertEquals("prod", resolver.resolve(null));
        assertEquals("prod", resolver.resolve(new GatewayContext()));

        setDefaultEnv(null);
        assertEquals("prod", resolver.resolve(new GatewayContext()), "默认环境为 null 时也必须给出 prod");
    }

    @Test
    @DisplayName("头值首尾空白被裁剪（避免「 test 」这类脏值写进日志与匹配条件）")
    void trimsHeaderValue() {
        assertEquals("gray", resolver.resolve(ctxWithHeaders("  gray  ", null)));
    }
}
