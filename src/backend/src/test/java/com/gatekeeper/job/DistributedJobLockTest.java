package com.gatekeeper.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集群定时任务锁单元测试
 *
 * <p>钉死四件事：抢到 / 抢不到 / Redis 异常的两种策略 / 释放不误删他人锁。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DistributedJobLock：集群任务互斥")
class DistributedJobLockTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private DistributedJobLock lock;

    @BeforeEach
    void setUp() {
        lock = new DistributedJobLock(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("抢到锁 → true，且键带 TTL 前缀正确")
    void tryLock_whenAcquired_returnsTrue() {
        when(valueOps.setIfAbsent(eq(DistributedJobLock.KEY_PREFIX + "job-a"),
                anyString(), any(Duration.class))).thenReturn(true);

        assertTrue(lock.tryLock("job-a", Duration.ofSeconds(60), false));
    }

    @Test
    @DisplayName("锁被他人持有 → false（无论哪种 fail 策略都不放行）")
    void tryLock_whenBusy_returnsFalseForBothPolicies() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertFalse(lock.tryLock("job-a", Duration.ofSeconds(60), false));
        assertFalse(lock.tryLock("job-b", Duration.ofSeconds(60), true));
    }

    @Test
    @DisplayName("Redis 异常 + fail-open → 放行（幂等任务：宁可重复做，不可不做）")
    void tryLock_whenRedisDownAndFailOpen_returnsTrue() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertTrue(lock.tryLock("grant-expire:2026-09-30", Duration.ofHours(25), true));
    }

    @Test
    @DisplayName("Redis 异常 + fail-safe → 跳过（有副作用任务：宁可不做，不可重复做）")
    void tryLock_whenRedisDownAndFailSafe_returnsFalse() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertFalse(lock.tryLock("alarm-realtime", Duration.ofSeconds(60), false));
    }

    @Test
    @DisplayName("返回 null（异常语义）时按调用方策略处理，不误判为抢到")
    void tryLock_whenNull_usesPolicy() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        assertFalse(lock.tryLock("job-a", Duration.ofSeconds(60), false));
        assertTrue(lock.tryLock("job-a", Duration.ofSeconds(60), true));
    }

    @Test
    @DisplayName("释放锁走 Lua 脚本（仅当值等于本节点 instanceId 才 DEL，防误删他人锁）")
    @SuppressWarnings("unchecked")
    void unlock_executesReleaseScriptWithInstanceId() {
        lock.unlock("job-a");

        verify(redisTemplate).execute(any(RedisScript.class),
                eq(java.util.Collections.singletonList(DistributedJobLock.KEY_PREFIX + "job-a")),
                eq(lock.getInstanceId()));
    }

    @Test
    @DisplayName("释放锁失败不抛异常（锁有 TTL 兜底，不会永久死锁）")
    @SuppressWarnings("unchecked")
    void unlock_swallowsRedisError() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> lock.unlock("job-a"));
    }

    @Test
    @DisplayName("空任务名不放行锁逻辑、不误删键，但也绝不让任务永久不执行")
    void tryLock_blankJobName_failsOpen() {
        assertTrue(lock.tryLock("", Duration.ofSeconds(60), false));
        assertTrue(lock.tryLock(null, Duration.ofSeconds(60), false));
        assertDoesNotThrow(() -> lock.unlock(null));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("daily() 生成「任务名:yyyy-MM-dd」的日期戳键，保证当日恰好一次")
    void daily_appendsTodayDate() {
        assertEquals("grant-expire:" + LocalDate.now(), DistributedJobLock.daily("grant-expire"));
    }

    @Test
    @DisplayName("instanceId 非空且稳定（同一实例重复读取一致）")
    void instanceId_isStable() {
        List<String> ids = java.util.Arrays.asList(lock.getInstanceId(), lock.getInstanceId());
        assertTrue(ids.get(0) != null && !ids.get(0).isEmpty());
        assertEquals(ids.get(0), ids.get(1));
    }
}
