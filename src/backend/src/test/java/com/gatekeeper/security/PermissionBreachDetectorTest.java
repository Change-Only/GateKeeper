package com.gatekeeper.security;

import com.gatekeeper.service.AlertService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限越界检测器单元测试
 *
 * <p>覆盖：达到阈值发布告警、自动封禁开关（开/关）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionBreachDetectorTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SecurityEventRecorder recorder;
    @Mock
    private AlertService alertService;
    @Mock
    private BanExecutor banExecutor;

    private PermissionBreachDetector detector;

    @BeforeEach
    void setUp() throws Exception {
        detector = new PermissionBreachDetector(redisTemplate, recorder, alertService, banExecutor);
        setInt(detector, "windowMin", 10);
        setInt(detector, "threshold", 20);
        setInt(detector, "banDurationMin", 60);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private void setInt(Object target, String field, int value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(target, value);
    }

    private void setBoolean(Object target, String field, boolean value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setBoolean(target, value);
    }

    @Test
    void detect_whenReachThreshold_shouldPublishWarning() {
        when(valueOps.increment("perm_breach:1")).thenReturn(20L);

        detector.detect(1L, "订单服务", "1.2.3.4", 100L, "/api/order");

        verify(alertService, times(1)).publish(eq("WARNING"), eq("SECURITY"), eq("权限越界预警"), any(), any(), any(), any());
        verify(banExecutor, never()).ban(any(), any(), anyInt()); // 默认不自动封禁
    }

    @Test
    void detect_whenAutoBanEnabled_shouldBanIp() throws Exception {
        setBoolean(detector, "autoBan", true);
        when(valueOps.increment("perm_breach:2")).thenReturn(20L);

        detector.detect(2L, "订单服务", "1.2.3.4", 100L, "/api/order");

        verify(banExecutor, times(1)).ban(eq("1.2.3.4"), eq("权限越界20次"), anyInt());
    }

    @Test
    void detect_whenBelowThreshold_shouldNotPublish() {
        when(valueOps.increment("perm_breach:3")).thenReturn(5L);

        detector.detect(3L, "订单服务", "1.2.3.4", 100L, "/api/order");

        verify(alertService, never()).publish(any(), any(), any(), any(), any(), any(), any());
    }
}
