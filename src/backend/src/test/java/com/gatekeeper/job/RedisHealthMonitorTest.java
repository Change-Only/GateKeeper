package com.gatekeeper.job;

import com.gatekeeper.service.AlertService;
import org.junit.jupiter.api.BeforeEach;
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
 * <p>覆盖：健康时不告警；故障发布 CRITICAL（仅一次防刷屏）；恢复发布 INFO。</p>
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
}
