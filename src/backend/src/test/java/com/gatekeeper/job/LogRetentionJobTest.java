package com.gatekeeper.job;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.mapper.ApiCallLogMapper;
import com.gatekeeper.service.ExportTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日志保留清理定时任务单元测试
 *
 * <p>覆盖：调用日志按保留天数删除、导出任务清理被调用、单项异常不中断另一项、
 * 以及集群下「当天已由其他节点执行则整体跳过」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogRetentionJobTest {

    @Mock
    private ApiCallLogMapper apiCallLogMapper;
    @Mock
    private ExportTaskService exportTaskService;
    @Mock
    private DistributedJobLock jobLock;

    private LogRetentionJob job;

    @BeforeEach
    void setUp() throws Exception {
        job = new LogRetentionJob(apiCallLogMapper, exportTaskService, jobLock);
        Field f = LogRetentionJob.class.getDeclaredField("retentionDays");
        f.setAccessible(true);
        f.setInt(job, 90);
        when(jobLock.tryLock(anyString(), any(Duration.class), anyBoolean())).thenReturn(true);
    }

    @Test
    void cleanup_shouldDeleteExpiredCallLogs() {
        when(apiCallLogMapper.delete(any(QueryWrapper.class))).thenReturn(1000);

        job.cleanup();

        verify(apiCallLogMapper).delete(any(QueryWrapper.class));
        verify(exportTaskService).cleanupExpired(LogRetentionJob.EXPORT_RETENTION_DAYS);
    }

    @Test
    void cleanup_whenLogDeleteFails_shouldStillCleanExports() {
        when(apiCallLogMapper.delete(any(QueryWrapper.class))).thenThrow(new RuntimeException("db down"));

        job.cleanup();

        verify(exportTaskService).cleanupExpired(LogRetentionJob.EXPORT_RETENTION_DAYS);
    }

    @Test
    void cleanup_whenExportCleanupFails_shouldNotAffectOthers() {
        when(exportTaskService.cleanupExpired(anyInt())).thenThrow(new RuntimeException("io error"));

        job.cleanupCallLogs();
        verify(apiCallLogMapper).delete(any(QueryWrapper.class));
        verify(exportTaskService, never()).cleanupExpired(anyInt());
    }

    @Test
    @DisplayName("集群：当天已由其他节点执行 → 两项清理都不做")
    void cleanup_whenDailyLockNotAcquired_shouldSkipEverything() {
        when(jobLock.tryLock(anyString(), any(Duration.class), anyBoolean())).thenReturn(false);

        job.cleanup();

        verify(apiCallLogMapper, never()).delete(any(QueryWrapper.class));
        verify(exportTaskService, never()).cleanupExpired(anyInt());
    }

    @Test
    @DisplayName("集群：用「日期戳」锁键保证当日恰好一次，且用 fail-open 策略")
    void cleanup_shouldUseDailyKeyWithFailOpen() {
        job.cleanup();

        verify(jobLock).tryLock(eq(DistributedJobLock.daily(LogRetentionJob.JOB_NAME)),
                any(Duration.class), eq(true));
    }

    @Test
    @DisplayName("集群：日戳锁不释放（靠 25h TTL 过期，保证当天其余节点继续跳过）")
    void cleanup_shouldNotReleaseDailyLock() {
        job.cleanup();

        verify(jobLock, never()).unlock(anyString());
    }
}
