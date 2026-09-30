package com.gatekeeper.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gatekeeper.service.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 健康监控单元测试
 *
 * <p>覆盖两组语义：</p>
 * <ol>
 *   <li><b>单节点</b>：健康时不告警；故障发布 CRITICAL（仅一次防刷屏）；恢复发布 INFO；</li>
 *   <li><b>集群去重</b>：本任务因「触发条件即 Redis 不可用」而无法用 Redis 锁协调，
 *       改由 DB 侧查询「窗口内是否已有未处理的同标题告警」来避免 N 个节点重复告警。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisHealthMonitorTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private RedisConnectionFactory factory;
    @Mock
    private RedisConnection connection;
    @Mock
    private AlertService alertService;

    private RedisHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new RedisHealthMonitor(redisTemplate, alertService);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
    }

    @Test
    void check_whenHealthy_shouldNotPublish() {
        monitor.check();

        verify(alertService, never()).publish(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void check_whenRedisDown_shouldPublishCriticalOnceThenRecover() {
        // 前两次 ping 失败（故障持续），第三次恢复
        when(connection.ping()).thenThrow(new RuntimeException("connection refused"))
                .thenThrow(new RuntimeException("connection refused"))
                .thenReturn("PONG");

        monitor.check(); // 故障：publish CRITICAL
        monitor.check(); // 仍故障：不重复告警
        monitor.check(); // 恢复：publish INFO

        verify(alertService, times(1)).publish(eq("CRITICAL"), eq("SYSTEM"), eq("Redis 不可用"), any(), any(), any(), any());
        verify(alertService, times(1)).publish(eq("INFO"), eq("SYSTEM"), eq("Redis 已恢复"), any(), any(), any(), any());
    }

    // ============================================================
    // 集群去重
    // ============================================================

    @Test
    @DisplayName("集群：故障时窗口内已有未处理同标题告警 → 本节点不重复发布")
    void check_whenRedisDownButAlertAlreadyExists_shouldNotPublishDuplicate() {
        when(connection.ping()).thenThrow(new RuntimeException("connection refused"));
        // 其他节点已报过：窗口内存在未处理的「Redis 不可用」
        when(alertService.count(any(Wrapper.class))).thenReturn(1L);

        monitor.check();

        verify(alertService, never()).publish(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("集群：窗口内没有同标题告警（本节点第一个发现）→ 正常发布")
    void check_whenRedisDownAndNoExistingAlert_shouldPublish() {
        when(connection.ping()).thenThrow(new RuntimeException("connection refused"));
        when(alertService.count(any(Wrapper.class))).thenReturn(0L);

        monitor.check();

        verify(alertService, times(1))
                .publish(eq("CRITICAL"), eq("SYSTEM"), eq(RedisHealthMonitor.TITLE_DOWN), any(), any(), any(), any());
    }

    @Test
    @DisplayName("集群：去重查询本身失败（DB 同时不可用）→ 按未重复处理，宁可多发不漏报")
    void check_whenDedupQueryFails_shouldStillPublish() {
        when(connection.ping()).thenThrow(new RuntimeException("connection refused"));
        when(alertService.count(any(Wrapper.class))).thenThrow(new RuntimeException("db down"));

        monitor.check();

        verify(alertService, times(1))
                .publish(eq("CRITICAL"), eq("SYSTEM"), eq(RedisHealthMonitor.TITLE_DOWN), any(), any(), any(), any());
    }

    @Test
    @DisplayName("集群：已有恢复告警时，恢复阶段也不重复发布 INFO")
    void check_whenRecoveredButAlertAlreadyExists_shouldNotPublishDuplicate() {
        when(connection.ping())
                .thenThrow(new RuntimeException("connection refused"))
                .thenReturn("PONG");
        // 故障期：本节点是第一个 → 发布 CRITICAL；随后窗口内出现恢复告警 → 恢复期不重复
        when(alertService.count(any(Wrapper.class))).thenReturn(0L, 1L);

        monitor.check();   // 故障 → 发布 CRITICAL
        monitor.check();   // 恢复 → 已有同标题 → 不发布

        verify(alertService, times(1)).publish(eq("CRITICAL"), any(), any(), any(), any(), any(), any());
        verify(alertService, never()).publish(eq("INFO"), any(), any(), any(), any(), any(), any());
    }
}
