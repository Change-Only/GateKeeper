package com.gatekeeper.controller;

import com.gatekeeper.block.BlockRuleController;
import com.gatekeeper.block.BlockRuleService;
import com.gatekeeper.block.ManualBlockRequest;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.BlockRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BlockRuleController 端到端单测 — 镜像 AppCredentialControllerTest 风格
 *
 * <p>覆盖 6 个接口 + 权限注解（2 个高危：create / manual-block）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BlockRuleController 端到端 + 权限点")
class BlockRuleControllerTest {

    @Mock
    private BlockRuleService blockRuleService;

    private BlockRuleController controller;

    @BeforeEach
    void setUp() {
        controller = new BlockRuleController(blockRuleService);
    }

    @Test
    @DisplayName("list 调用 service.list")
    void list_callsService() {
        BlockRule rule = new BlockRule();
        rule.setId(1L);
        rule.setScope("IP");
        rule.setReasonCode("IP_NOT_ALLOWED");
        when(blockRuleService.list()).thenReturn(Collections.singletonList(rule));

        Result<List<BlockRule>> r = controller.list();
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().size());
        verify(blockRuleService, times(1)).list();
    }
    @Test
    @DisplayName("create 标注 @RequirePerm block_rule:create 高危")
    void create_hasCreatePermAnnotation() throws NoSuchMethodException {
        com.gatekeeper.security.RequirePerm ann =
                BlockRuleController.class.getMethod("create", BlockRule.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann);
        assertEquals("block_rule:create", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("create 调用 service.create")
    void create_callsService() {
        BlockRule rule = new BlockRule();
        rule.setId(1L);
        when(blockRuleService.create(any(BlockRule.class))).thenReturn(rule);

        Result<BlockRule> r = controller.create(rule);
        assertEquals(200, r.getCode());
        verify(blockRuleService, times(1)).create(any(BlockRule.class));
    }

    @Test
    @DisplayName("update 调用 service.update")
    void update_callsService() {
        BlockRule rule = new BlockRule();
        Result<Void> r = controller.update(5L, rule);
        assertEquals(200, r.getCode());
        verify(blockRuleService, times(1)).update(eq(5L), eq(rule));
    }

    @Test
    @DisplayName("toggle 调用 service.toggle")
    void toggle_callsService() {
        BlockRule rule = new BlockRule();
        rule.setId(7L);
        when(blockRuleService.toggle(eq(7L), eq(1))).thenReturn(rule);

        Result<BlockRule> r = controller.toggle(7L, 1);
        assertEquals(200, r.getCode());
        verify(blockRuleService, times(1)).toggle(eq(7L), eq(1));
    }

    @Test
    @DisplayName("manualBlock 标注 @RequirePerm block_rule:manual 高危")
    void manualBlock_hasManualPermAnnotation() throws NoSuchMethodException {
        com.gatekeeper.security.RequirePerm ann =
                BlockRuleController.class.getMethod("manualBlock", Long.class, ManualBlockRequest.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann);
        assertEquals("block_rule:manual", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("manualBlock 调用 service.manualBlock")
    void manualBlock_callsService() {
        ManualBlockRequest req = new ManualBlockRequest();
        req.setTarget("1.2.3.4");

        Result<Void> r = controller.manualBlock(9L, req);
        assertEquals(200, r.getCode());
        verify(blockRuleService, times(1)).manualBlock(eq(9L), eq("1.2.3.4"), any(), any());
    }

    @Test
    @DisplayName("5 个接口全部存在（路由核查）")
    void allEndpointsExist() throws NoSuchMethodException {
        Class<?> c = BlockRuleController.class;
        assertNotNull(c.getMethod("list"));
        assertNotNull(c.getMethod("create", BlockRule.class));
        assertNotNull(c.getMethod("update", Long.class, BlockRule.class));
        assertNotNull(c.getMethod("toggle", Long.class, Integer.class));
        assertNotNull(c.getMethod("manualBlock", Long.class, ManualBlockRequest.class));
    }
}
