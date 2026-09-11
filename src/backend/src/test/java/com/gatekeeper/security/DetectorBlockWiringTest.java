package com.gatekeeper.security;

import com.gatekeeper.block.BlockExecutor;
import com.gatekeeper.service.AlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 5 个检测器 → BlockExecutor 埋点接线测试
 *
 * <p>验证：每个检测器 detect() 触发时至少调用一次 BlockExecutor.evaluateAndBan，
 * 且保留既有 recorder.record 调用；BlockExecutor 抛错时 detect() 绝不向外传播异常。</p>
 *
 * <p>注：为保持存量检测器构造函数不变（存量 *DetectorTest 通过显式构造函数构造，
 * 不在本任务可修改范围），BlockExecutor 以 {@code @Autowired} 字段注入，本测试用反射注入 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("5 检测器 → BlockExecutor 埋点接线（失败开放）")
class DetectorBlockWiringTest {

    private static void setBlockExecutor(Object detector, BlockExecutor blockExecutor) throws Exception {
        Field f = detector.getClass().getDeclaredField("blockExecutor");
        f.setAccessible(true);
        f.set(detector, blockExecutor);
    }

    private static void setInt(Object target, String field, int value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(target, value);
    }

    // ====================== AuthFailDetector ======================

    @Test
    @DisplayName("AuthFailDetector.detect 触发 BlockExecutor 且保留 recorder.record")
    void authFail_firesBlockExecutor() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        BanExecutor ban = mock(BanExecutor.class);
        BlockExecutor blockExecutor = mock(BlockExecutor.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        AuthFailDetector detector = new AuthFailDetector(redis, recorder, ban);
        setInt(detector, "windowMin", 5);
        setInt(detector, "threshold", 10);
        setInt(detector, "banDurationMin", 60);
        setBlockExecutor(detector, blockExecutor);

        detector.detect("1.2.3.4", "appKey", "Invalid signature");

        verify(blockExecutor, times(1)).evaluateAndBan(eq("IP"), eq("IP_NOT_ALLOWED"), eq("1.2.3.4"), anyInt());
        verify(recorder, times(1)).record(eq("AUTH_FAIL"), any(), any(), any(), eq("1.2.3.4"), any());
    }

    @Test
    @DisplayName("AuthFailDetector.detect 在 BlockExecutor 抛错时不抛异常")
    void authFail_blockExecutorThrows_noPropagate() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        BanExecutor ban = mock(BanExecutor.class);
        BlockExecutor blockExecutor = mock(BlockExecutor.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        doThrow(new RuntimeException("boom")).when(blockExecutor).evaluateAndBan(any(), any(), any(), anyInt());

        AuthFailDetector detector = new AuthFailDetector(redis, recorder, ban);
        setBlockExecutor(detector, blockExecutor);
        // 单元测试无 Spring 注入，@Value 字段为 int 默认值 0（阈值 0 会导致每次都触发封禁分支）。
        // 通过反射设定与生产一致的阈值，使 count=1 时不进入封禁分支，仅记录一次 AUTH_FAIL。
        setInt(detector, "threshold", 10);

        assertDoesNotThrow(() -> detector.detect("1.2.3.4", "appKey", "Invalid signature"));
        verify(recorder, times(1)).record(eq("AUTH_FAIL"), any(), any(), any(), eq("1.2.3.4"), any());
    }

    // ====================== PermissionBreachDetector ======================

    @Test
    @DisplayName("PermissionBreachDetector.detect 触发 BlockExecutor 且保留 recorder.record")
    void permissionBreach_firesBlockExecutor() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        AlertService alertService = mock(AlertService.class);
        BanExecutor ban = mock(BanExecutor.class);
        BlockExecutor blockExecutor = mock(BlockExecutor.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        PermissionBreachDetector detector = new PermissionBreachDetector(redis, recorder, alertService, ban);
        setBlockExecutor(detector, blockExecutor);

        detector.detect(1L, "订单服务", "1.2.3.4", 100L, "/api/order");

        verify(blockExecutor, times(1)).evaluateAndBan(eq("APP"), eq("SIGNATURE_MISMATCH"), eq("1"), anyInt());
        verify(recorder, times(1)).record(eq("PERMISSION_BREACH"), any(), eq(1L), any(), any(), any());
    }

    @Test
    @DisplayName("PermissionBreachDetector.detect 在 BlockExecutor 抛错时不抛异常")
    void permissionBreach_blockExecutorThrows_noPropagate() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        AlertService alertService = mock(AlertService.class);
        BanExecutor ban = mock(BanExecutor.class);
        BlockExecutor blockExecutor = mock(BlockExecutor.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        doThrow(new RuntimeException("boom")).when(blockExecutor).evaluateAndBan(any(), any(), any(), anyInt());

        PermissionBreachDetector detector = new PermissionBreachDetector(redis, recorder, alertService, ban);
        setBlockExecutor(detector, blockExecutor);

        assertDoesNotThrow(() -> detector.detect(1L, "订单服务", "1.2.3.4", 100L, "/api/order"));
        verify(recorder, times(1)).record(eq("PERMISSION_BREACH"), any(), eq(1L), any(), any(), any());
    }

    // ====================== AbnormalParamDetector ======================

    @Test
    @DisplayName("AbnormalParamDetector.detect 触发 BlockExecutor 且保留 recorder.record")
    void abnormalParam_firesBlockExecutor() throws Exception {
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        AlertService alertService = mock(AlertService.class);
        BlockExecutor blockExecutor = mock(BlockExecutor.class);

        AbnormalParamDetector detector = new AbnormalParamDetector(recorder, alertService);
        setBlockExecutor(detector, blockExecutor);

        detector.detect("1.2.3.4", 1L, "订单服务", "SQL注入特征");

        verify(blockExecutor, times(1)).evaluateAndBan(eq("IP"), eq("SIGNATURE_MISMATCH"), eq("1.2.3.4"), eq(1));
        verify(recorder, times(1)).record(eq("ABNORMAL_PARAM"), any(), eq(1L), any(), eq("1.2.3.4"), any());
    }

    @Test
    @DisplayName("AbnormalParamDetector.detect 在 BlockExecutor 抛错时不抛异常")
    void abnormalParam_blockExecutorThrows_noPropagate() throws Exception {
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        AlertService alertService = mock(AlertService.class);
        BlockExecutor blockExecutor = mock(BlockExecutor.class);
        doThrow(new RuntimeException("boom")).when(blockExecutor).evaluateAndBan(any(), any(), any(), anyInt());

        AbnormalParamDetector detector = new AbnormalParamDetector(recorder, alertService);
        setBlockExecutor(detector, blockExecutor);

        assertDoesNotThrow(() -> detector.detect("1.2.3.4", 1L, "订单服务", "SQL注入特征"));
        verify(recorder, times(1)).record(eq("ABNORMAL_PARAM"), any(), eq(1L), any(), eq("1.2.3.4"), any());
    }

    // ====================== HighFrequencyDetector ======================

    @Test
    @DisplayName("HighFrequencyDetector.detect 触发 BlockExecutor 且保留 recorder.record")
    void highFrequency_firesBlockExecutor() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        AlertService alertService = mock(AlertService.class);
        BlockExecutor blockExecutor = mock(BlockExecutor.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(3000L);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        HighFrequencyDetector detector = new HighFrequencyDetector(redis, recorder, alertService);
        setInt(detector, "qps", 100);
        setInt(detector, "windowSec", 30);
        setBlockExecutor(detector, blockExecutor);

        detector.detect(1L, "订单服务");

        verify(blockExecutor, times(1)).evaluateAndBan(eq("APP"), eq("RATE_LIMIT_EXCEEDED"), eq("1"), anyInt());
        verify(recorder, times(1)).record(eq("HIGH_FREQUENCY"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("HighFrequencyDetector.detect 在 BlockExecutor 抛错时不抛异常")
    void highFrequency_blockExecutorThrows_noPropagate() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        AlertService alertService = mock(AlertService.class);
        BlockExecutor blockExecutor = mock(BlockExecutor.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(3000L);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(blockExecutor).evaluateAndBan(any(), any(), any(), anyInt());

        HighFrequencyDetector detector = new HighFrequencyDetector(redis, recorder, alertService);
        setInt(detector, "qps", 100);
        setInt(detector, "windowSec", 30);
        setBlockExecutor(detector, blockExecutor);

        assertDoesNotThrow(() -> detector.detect(1L, "订单服务"));
        verify(recorder, times(1)).record(eq("HIGH_FREQUENCY"), any(), any(), any(), any(), any());
    }

    // ====================== OffHoursDetector ======================

    @Test
    @DisplayName("OffHoursDetector.detect 触发 BlockExecutor 且保留 recorder.record")
    void offHours_firesBlockExecutor() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        BlockExecutor blockExecutor = mock(BlockExecutor.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        OffHoursDetector detector = new OffHoursDetector(redis, recorder);
        // start=0,end=0 → isOffHours 对任意小时恒为异常时段，保证检测触发
        setInt(detector, "startHour", 0);
        setInt(detector, "endHour", 0);
        setBlockExecutor(detector, blockExecutor);

        detector.detect(1L, "订单服务", "1.2.3.4");

        verify(blockExecutor, times(1)).evaluateAndBan(eq("APP"), eq("IP_NOT_ALLOWED"), eq("1"), eq(1));
        verify(recorder, times(1)).record(eq("OFF_HOURS"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("OffHoursDetector.detect 在 BlockExecutor 抛错时不抛异常")
    void offHours_blockExecutorThrows_noPropagate() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        SecurityEventRecorder recorder = mock(SecurityEventRecorder.class);
        BlockExecutor blockExecutor = mock(BlockExecutor.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(blockExecutor).evaluateAndBan(any(), any(), any(), anyInt());

        OffHoursDetector detector = new OffHoursDetector(redis, recorder);
        setInt(detector, "startHour", 0);
        setInt(detector, "endHour", 0);
        setBlockExecutor(detector, blockExecutor);

        assertDoesNotThrow(() -> detector.detect(1L, "订单服务", "1.2.3.4"));
        verify(recorder, times(1)).record(eq("OFF_HOURS"), any(), any(), any(), any(), any());
    }
}
