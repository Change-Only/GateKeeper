package com.gatekeeper.gateway.handler;

import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.security.banner.IpBanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * IP 封禁检查降级单元测试
 *
 * <p>覆盖：Redis 正常时命中封禁拒绝；Redis 异常时 fail-open 降级放行（业务不中断）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IpBanCheckHandlerTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private IpBanService ipBanService;

    private IpBanCheckHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        handler = new IpBanCheckHandler(redisTemplate, ipBanService);
        Field f = IpBanCheckHandler.class.getDeclaredField("redisFailOpen");
        f.setAccessible(true);
        f.setBoolean(handler, true);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private GatewayContext ctx() {
        GatewayContext ctx = new GatewayContext();
        ctx.setClientIp("1.2.3.4");
        ctx.setAppId(1L);
        return ctx;
    }

    @Test
    void handle_whenBanned_shouldReject() {
        when(valueOps.get(anyString())).thenReturn("高频调用封禁");

        assertThrows(GatewayException.class, () -> handler.handle(ctx()), "命中封禁应拒绝");
    }

    @Test
    void handle_whenRedisDown_shouldFailOpen() {
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertDoesNotThrow(() -> handler.handle(ctx()), "Redis 故障应降级放行");
    }
}
