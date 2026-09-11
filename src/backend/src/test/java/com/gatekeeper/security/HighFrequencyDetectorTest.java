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
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 高频调用检测器单元测试
 *
 * <p>覆盖：达到阈值 + 首次告警 → 记录事件与发布告警；去重后不重复告警。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HighFrequencyDetectorTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SecurityEventRecorder recorder;
    @Mock
    private AlertService alertService;

    private HighFrequencyDetector detector;

    @BeforeEach
    void setUp() throws Exception {
        detector = new HighFrequencyDetector(redisTemplate, recorder, alertService);
        setInt(detector, "qps", 100);
        setInt(detector, "windowSec", 30);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    private void setInt(Object target, String field, int value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(target, value);
    }

    @Test
    void detect_whenReachThresholdAndFirstAlert_shouldRecordAndPublish() {
        when(valueOps.increment(anyString())).thenReturn(3000L); // 100 * 30
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        detector.detect(1L, "订单服务");

        verify(recorder, times(1)).record(eq("HIGH_FREQUENCY"), any(), any(), any(), any(), any());
        verify(alertService, times(1)).publish(eq("WARNING"), eq("RATE_LIMIT"), eq("高频调用告警"), any(), any(), any(), any());
    }

    @Test
    void detect_whenAlreadyAlerted_shouldNotRepeat() {
        when(valueOps.increment(anyString())).thenReturn(3000L);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        detector.detect(1L, "订单服务");

        verify(recorder, never()).record(any(), any(), any(), any(), any(), any());
        verify(alertService, never()).publish(any(), any(), any(), any(), any(), any(), any());
    }
}
