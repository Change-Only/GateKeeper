package com.gatekeeper.gateway.handler;

import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.gateway.EnvResolver;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.service.ApiVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * VersionRouteHandler 单元测试
 *
 * <p>验证：ctx.version / ctx.envCode 被正确写入；已存在 envCode 不被覆盖；
 * 以及 FAIL-OPEN 降级（apiVersionService 抛异常时不抛错、version 不写入）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VersionRouteHandlerTest {

    @Mock
    private ApiVersionService apiVersionService;

    private EnvResolver envResolver;
    private VersionRouteHandler handler;

    @BeforeEach
    void setUp() {
        // EnvResolver 为纯工具，默认回退 "prod"，无需 Spring 注入
        envResolver = new EnvResolver();
        handler = new VersionRouteHandler(apiVersionService, envResolver);
    }

    private GatewayContext ctx(Long interfaceId, Long appId) {
        GatewayContext ctx = new GatewayContext();
        ctx.setInterfaceId(interfaceId);
        ctx.setAppId(appId);
        return ctx;
    }

    private ApiVersionDto current(String version) {
        ApiVersionDto d = new ApiVersionDto();
        d.setId(1L);
        d.setApiId(10L);
        d.setVersion(version);
        d.setIsCurrent(1);
        d.setGrayRatio(0);
        return d;
    }

    private ApiVersionDto gray(String version, int ratio) {
        ApiVersionDto d = new ApiVersionDto();
        d.setId(2L);
        d.setApiId(10L);
        d.setVersion(version);
        d.setIsCurrent(0);
        d.setGrayRatio(ratio);
        return d;
    }

    @Test
    void handle_setsVersionAndEnvCode() {
        when(apiVersionService.list(10L))
                .thenReturn(Arrays.asList(current("v1"), gray("v2", 0)));
        GatewayContext ctx = ctx(10L, 123L);

        handler.handle(ctx);

        assertEquals("v1", ctx.getVersion(), "默认（grayRatio=0）应路由到 current 版本");
        assertEquals("prod", ctx.getEnvCode(), "未指定环境时应回退到默认 prod");
    }

    @Test
    void handle_doesNotOverrideExistingEnvCode() {
        when(apiVersionService.list(10L)).thenReturn(Collections.singletonList(current("v1")));
        GatewayContext ctx = ctx(10L, 123L);
        ctx.setEnvCode("pre");

        handler.handle(ctx);

        assertEquals("pre", ctx.getEnvCode(), "已存在的 envCode 不应被覆盖");
        assertEquals("v1", ctx.getVersion());
    }

    @Test
    void handle_failOpen_whenServiceThrows() {
        when(apiVersionService.list(anyLong())).thenThrow(new RuntimeException("db down"));
        GatewayContext ctx = ctx(10L, 123L);

        // 服务异常时不得抛出，且 version 保持未设置（fail-open）
        assertDoesNotThrow(() -> handler.handle(ctx), "apiVersionService 异常应 fail-open 继续");
        assertNull(ctx.getVersion(), "fail-open 时 version 不应被写入");
    }

    @Test
    void handle_skipWhenNoInterface() {
        GatewayContext ctx = ctx(null, 123L);
        assertDoesNotThrow(() -> handler.handle(ctx));
        assertNull(ctx.getVersion());
    }
}
