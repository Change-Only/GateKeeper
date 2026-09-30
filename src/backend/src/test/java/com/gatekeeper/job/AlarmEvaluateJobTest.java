package com.gatekeeper.job;

import com.gatekeeper.alarm.AlarmRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlarmEvaluateJob 单测
 *
 * <p>覆盖两组口径：</p>
 * <ol>
 *   <li><b>fail-open（业务异常）</b>：定时方法在常规与异常情况下均不向外抛异常；</li>
 *   <li><b>集群互斥</b>：抢不到分布式锁时不执行评估、也不释放不属于自己的锁。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AlarmEvaluateJob：调度不抛异常 + 集群互斥")
class AlarmEvaluateJobTest {

    @Mock
    private AlarmRuleService alarmRuleService;
    @Mock
    private DistributedJobLock jobLock;

    private AlarmEvaluateJob job;

    @BeforeEach
    void setUp() {
        job = new AlarmEvaluateJob(alarmRuleService, jobLock);
        when(jobLock.tryLock(anyString(), any(Duration.class), anyBoolean())).thenReturn(true);
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

    @Test
    @DisplayName("集群：抢到锁 → 执行并释放锁")
    void whenLockAcquired_shouldEvaluateAndRelease() {
        job.realtimeEvaluate();

        verify(alarmRuleService).evaluateRealtime();
        verify(jobLock).unlock(AlarmEvaluateJob.JOB_REALTIME);
    }

    @Test
    @DisplayName("集群：抢不到锁 → 跳过评估（避免重复告警）且不释放他人锁")
    void whenLockNotAcquired_shouldSkipWithoutReleasing() {
        when(jobLock.tryLock(anyString(), any(Duration.class), anyBoolean())).thenReturn(false);

        job.realtimeEvaluate();

        verify(alarmRuleService, never()).evaluateRealtime();
        verify(jobLock, never()).unlock(anyString());
    }

    @Test
    @DisplayName("集群：评估抛异常时仍释放锁（否则锁要等 TTL 才回收）")
    void whenEvaluationThrows_shouldStillReleaseLock() {
        doThrow(new RuntimeException("boom")).when(alarmRuleService).evaluateOffline();

        job.offlineEvaluate();

        verify(jobLock).unlock(AlarmEvaluateJob.JOB_OFFLINE);
    }

    @Test
    @DisplayName("集群：评估类任务采用 fail-safe（Redis 异常时不放行）")
    void evaluationJobsUseFailSafe() {
        job.realtimeEvaluate();
        // 第三个参数 false = fail-safe：宁可少评估一轮，也不要多节点同时发告警
        verify(jobLock).tryLock(eq(AlarmEvaluateJob.JOB_REALTIME), any(Duration.class), eq(false));
    }
}
