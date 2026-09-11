package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import static org.mockito.Mockito.when;

/**
 * 频率限制降级单元测试
 *
 * <p>覆盖：Redis 故障时并发限制与日调用限制降级放行（fail-open），业务链路不中断。</p>
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

    private RateLimitHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RateLimitHandler(rateLimitMapper, redisTemplate);
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
}
