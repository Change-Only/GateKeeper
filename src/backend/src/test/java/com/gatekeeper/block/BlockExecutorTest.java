package com.gatekeeper.block;

import com.gatekeeper.entity.BlockRule;
import com.gatekeeper.mapper.BlockRuleMapper;
import com.gatekeeper.security.BanExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BlockExecutor 单元测试 — 规则评估 + 失败开放
 *
 * <p>覆盖：启用+自动+达阈值 → BanExecutor.ban；规则停用 → 不封禁；
 * 仅人工(autoBlock=0) → 不封禁；未达阈值 → 不封禁；查询异常 → 失败开放不抛且不计封禁。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BlockExecutor 规则评估 + 失败开放")
class BlockExecutorTest {

    @Mock
    private BlockRuleMapper blockRuleMapper;
    @Mock
    private BanExecutor banExecutor;

    private BlockExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new BlockExecutor(blockRuleMapper, banExecutor);
    }

    private BlockRule rule(Long id, String scope, String reasonCode, int enabled, int autoBlock, int threshold, int ttl) {
        BlockRule r = new BlockRule();
        r.setId(id);
        r.setScope(scope);
        r.setReasonCode(reasonCode);
        r.setEnabled(enabled);
        r.setAutoBlock(autoBlock);
        r.setThresholdCount(threshold);
        r.setTtlSeconds(ttl);
        r.setDescription("ut-rule");
        return r;
    }

    @Test
    @DisplayName("启用+自动+达阈值 → 调用 BanExecutor.ban")
    void enabled_auto_thresholdReached_shouldBan() {
        when(blockRuleMapper.selectList(any())).thenReturn(
                Collections.singletonList(rule(1L, "IP", "IP_NOT_ALLOWED", 1, 1, 5, 600)));

        executor.evaluateAndBan("IP", "IP_NOT_ALLOWED", "1.2.3.4", 10);

        verify(banExecutor, times(1)).ban(eq("1.2.3.4"), anyString(), eq(600));
    }

    @Test
    @DisplayName("规则停用 → 不封禁")
    void disabledRule_shouldNotBan() {
        when(blockRuleMapper.selectList(any())).thenReturn(
                Collections.singletonList(rule(2L, "IP", "IP_NOT_ALLOWED", 0, 1, 5, 600)));

        executor.evaluateAndBan("IP", "IP_NOT_ALLOWED", "1.2.3.4", 10);

        verify(banExecutor, never()).ban(any(), any(), anyInt());
    }

    @Test
    @DisplayName("仅人工(autoBlock=0) → 不封禁")
    void autoBlockOff_shouldNotBan() {
        when(blockRuleMapper.selectList(any())).thenReturn(
                Collections.singletonList(rule(3L, "IP", "IP_NOT_ALLOWED", 1, 0, 5, 600)));

        executor.evaluateAndBan("IP", "IP_NOT_ALLOWED", "1.2.3.4", 10);

        verify(banExecutor, never()).ban(any(), any(), anyInt());
    }

    @Test
    @DisplayName("未达阈值 → 不封禁")
    void belowThreshold_shouldNotBan() {
        when(blockRuleMapper.selectList(any())).thenReturn(
                Collections.singletonList(rule(4L, "IP", "IP_NOT_ALLOWED", 1, 1, 5, 600)));

        executor.evaluateAndBan("IP", "IP_NOT_ALLOWED", "1.2.3.4", 3);

        verify(banExecutor, never()).ban(any(), any(), anyInt());
    }

    @Test
    @DisplayName("查询异常 → 失败开放，不抛且不计封禁")
    void queryError_shouldNoOp() {
        when(blockRuleMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> executor.evaluateAndBan("IP", "IP_NOT_ALLOWED", "1.2.3.4", 10));
        verify(banExecutor, never()).ban(any(), any(), anyInt());
    }
}
