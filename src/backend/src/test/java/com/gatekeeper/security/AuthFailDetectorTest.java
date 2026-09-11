package com.gatekeeper.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 鉴权失败检测器单元测试
 *
 * <p>覆盖：达到阈值触发封禁、未达阈值不封禁、ip 为空短路。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthFailDetectorTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SecurityEventRecorder recorder;
    @Mock
    private BanExecutor banExecutor;

    private AuthFailDetector detector;

    @BeforeEach
    void setUp() throws Exception {
        detector = new AuthFailDetector(redisTemplate, recorder, banExecutor);
        setInt(detector, "windowMin", 5);
        setInt(detector, "threshold", 10);
        setInt(detector, "banDurationMin", 60);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private void setInt(Object target, String field, int value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(target, value);
    }

    @Test
    void detect_whenReachThreshold_shouldBanIp() {
        when(valueOps.increment("auth_fail:1.2.3.4:appKey")).thenReturn(10L);

        detector.detect("1.2.3.4", "appKey", "Invalid signature");

        verify(banExecutor, times(1)).ban("1.2.3.4", "连续鉴权失败10次", 60);
    }

    @Test
    void detect_whenFirstFailure_shouldSetWindowExpire() {
        when(valueOps.increment(any())).thenReturn(1L);

        detector.detect("1.2.3.4", "appKey", "Invalid signature");

        verify(redisTemplate).expire("auth_fail:1.2.3.4:appKey", 5, TimeUnit.MINUTES);
    }

    @Test
    void detect_whenBelowThreshold_shouldNotBan() {
        when(valueOps.increment(any())).thenReturn(3L);

        detector.detect("1.2.3.4", "appKey", "Invalid signature");

        verify(banExecutor, never()).ban(any(), any(), anyInt());
    }

    @Test
    void detect_whenIpNull_shouldShortCircuit() {
        detector.detect(null, "appKey", "Invalid signature");

        verify(valueOps, never()).increment(any());
        verify(banExecutor, never()).ban(any(), any(), anyInt());
    }
}
