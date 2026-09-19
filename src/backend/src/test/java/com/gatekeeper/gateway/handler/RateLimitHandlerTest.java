package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.config.SysConfigAccessor;
import com.gatekeeper.entity.AppRateLimit;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.AppRateLimitMapper;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 频率限制单元测试
 *
 * <p>覆盖：Redis 故障时并发限制与日调用限制降级放行（fail-open）、
 * 以及 T19 新增的 {@code gateway.ratelimit.enabled} 总开关（关闭时整环跳过）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitHandlerTest {

    @Mock
    private AppRateLimitMapper rateLimitMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SysConfigAccessor sysConfigAccessor;

    private RateLimitHandler handler;

    @BeforeEach
    void setUp() {
        // 默认视为「配置表里没有该键」⇒ 一律回退调用方传入的默认值（即限流开启）
        when(sysConfigAccessor.getBoolean(anyString(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));
        handler = new RateLimitHandler(rateLimitMapper, redisTemplate, sysConfigAccessor);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private GatewayContext ctx() {
        GatewayContext ctx = new GatewayContext();
        ctx.setAppId(1L);
        return ctx;
    }

    private AppRateLimit config(int qps, int concurrent, int daily) {
        AppRateLimit c = new AppRateLimit();
        c.setQpsLimit(qps);
        c.setConcurrentLimit(concurrent);
        c.setDailyLimit(daily);
        return c;
    }

    @Test
    void handle_whenRedisDown_shouldFailOpenForConcurrentLimit() {
        when(rateLimitMapper.selectOne(any(QueryWrapper.class))).thenReturn(config(0, 10, 0));
        when(valueOps.increment(any(String.class))).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertDoesNotThrow(() -> handler.handle(ctx()), "Redis 故障时并发限制应降级放行");
    }

    @Test
    void handle_whenRedisDown_shouldFailOpenForDailyLimit() {
        when(rateLimitMapper.selectOne(any(QueryWrapper.class))).thenReturn(config(0, 0, 1000));
        when(valueOps.increment(any(String.class))).thenThrow(new RedisConnectionFailureException("connection refused"));

        assertDoesNotThrow(() -> handler.handle(ctx()), "Redis 故障时日调用限制应降级放行");
    }

    /** T19：gateway.ratelimit.enabled=false 时整环跳过，且完全不触碰限流配置表 */
    @Test
    void handle_whenSwitchDisabled_shouldSkipWholeStage() {
        when(sysConfigAccessor.getBoolean(
                eq(SysConfigAccessor.KEY_GATEWAY_RATELIMIT_ENABLED), anyBoolean())).thenReturn(false);

        assertDoesNotThrow(() -> handler.handle(ctx()), "限流开关关闭时不应抛异常");
        verifyNoInteractions(rateLimitMapper);
        verifyNoInteractions(redisTemplate);
    }

    /** T19：开关默认开启（配置缺失回退 true），限流逻辑应照常执行 */
    @Test
    void handle_whenSwitchAbsent_shouldKeepRateLimiting() {
        when(rateLimitMapper.selectOne(any(QueryWrapper.class))).thenReturn(config(0, 10, 0));
        when(valueOps.increment(any(String.class))).thenReturn(1L);

        assertDoesNotThrow(() -> handler.handle(ctx()), "开关默认开启时不应跳过限流");
        verify(rateLimitMapper).selectOne(any(QueryWrapper.class));
        verify(valueOps).increment(any(String.class));
    }
}
