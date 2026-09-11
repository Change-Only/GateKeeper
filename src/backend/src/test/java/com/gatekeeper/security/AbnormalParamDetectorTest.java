package com.gatekeeper.security;

import com.gatekeeper.service.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 异常入参检测器单元测试
 *
 * <p>覆盖：记录安全事件 + 发布 WARNING 告警；appName 为 null 时降级为「未知」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbnormalParamDetectorTest {

    @Mock
    private SecurityEventRecorder recorder;
    @Mock
    private AlertService alertService;

    private AbnormalParamDetector detector;

    @BeforeEach
    void setUp() {
        detector = new AbnormalParamDetector(recorder, alertService);
    }

    @Test
    void detect_shouldRecordEventAndPublishWarning() {
        detector.detect("1.2.3.4", 1L, "订单服务", "SQL注入特征");

        verify(recorder, times(1)).record(eq("ABNORMAL_PARAM"), any(), eq(1L), eq("订单服务"), eq("1.2.3.4"), any());
        verify(alertService, times(1)).publish(eq("WARNING"), eq("SECURITY"), eq("异常入参检测"), any(), any(), any(), any());
    }

    @Test
    void detect_whenAppNameNull_shouldFallbackToUnknown() {
        detector.detect("1.2.3.4", null, null, "路径穿越特征");

        verify(alertService, times(1)).publish(eq("WARNING"), eq("SECURITY"), eq("异常入参检测"),
                org.mockito.ArgumentMatchers.contains("未知"), any(), any(), any());
    }
}
