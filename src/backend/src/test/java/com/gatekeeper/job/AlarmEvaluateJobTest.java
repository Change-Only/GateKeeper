package com.gatekeeper.job;

import com.gatekeeper.alarm.AlarmRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * AlarmEvaluateJob 单测 — 验证定时方法在常规与异常情况下均不向外抛异常（fail-open）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AlarmEvaluateJob 调度不抛异常")
class AlarmEvaluateJobTest {

    @Mock
    private AlarmRuleService alarmRuleService;

    private AlarmEvaluateJob job;

    @BeforeEach
    void setUp() {
        job = new AlarmEvaluateJob(alarmRuleService);
    }

    @Test
    @DisplayName("实时评估正常委托 service 且不抛异常")
    void realtimeRunsWithoutThrowing() {
        assertDoesNotThrow(() -> job.realtimeEvaluate());
        verify(alarmRuleService).evaluateRealtime();
    }

    @Test
    @DisplayName("离线评估正常委托 service 且不抛异常")
    void offlineRunsWithoutThrowing() {
        assertDoesNotThrow(() -> job.offlineEvaluate());
        verify(alarmRuleService).evaluateOffline();
    }

    @Test
    @DisplayName("service 抛异常时 job 仍不向外抛（fail-open）")
    void failOpenWhenServiceThrows() {
        doThrow(new RuntimeException("boom")).when(alarmRuleService).evaluateRealtime();
        assertDoesNotThrow(() -> job.realtimeEvaluate());
    }
}
