package com.gatekeeper.controller;

import com.gatekeeper.alarm.AlarmRuleController;
import com.gatekeeper.alarm.AlarmRuleService;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.AlarmRule;
import com.gatekeeper.security.RequirePerm;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlarmRuleController 端到端单测 — 覆盖 6 个接口 + 权限注解
 *
 * <p>重点验证 create 标注 {@code @RequirePerm("alarm_rule:create", risk=true)}，以及所有接口正确委托 service。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AlarmRuleController 端到端 + 权限点")
class AlarmRuleControllerTest {

    @Mock
    private AlarmRuleService alarmRuleService;

    private AlarmRuleController controller;

    @BeforeEach
    void setUp() {
        controller = new AlarmRuleController(alarmRuleService);
    }

    @Test
    @DisplayName("list 调用 service.list")
    void list_callsService() {
        when(alarmRuleService.list(any())).thenReturn(Collections.emptyList());
        Result<List<AlarmRule>> r = controller.list(null);
        assertEquals(200, r.getCode());
        verify(alarmRuleService, times(1)).list(null);
    }

    @Test
    @DisplayName("detail 调用 service.get")
    void detail_callsService() {
        AlarmRule rule = new AlarmRule();
        rule.setId(1L);
        when(alarmRuleService.get(1L)).thenReturn(rule);
        Result<AlarmRule> r = controller.detail(1L);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
    }

    @Test
    @DisplayName("create 调用 service.create")
    void create_callsService() {
        AlarmRule rule = new AlarmRule();
        rule.setId(1L);
        rule.setRuleName("失败率告警");
        when(alarmRuleService.create(any(AlarmRule.class))).thenReturn(rule);
        Result<AlarmRule> r = controller.create(rule);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
    }

    @Test
    @DisplayName("create 标注 @RequirePerm alarm_rule:create 高危")
    void create_hasCreatePermAnnotation() throws NoSuchMethodException {
        RequirePerm ann = AlarmRuleController.class.getMethod("create", AlarmRule.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("alarm_rule:create", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("update 调用 service.update")
    void update_callsService() {
        AlarmRule rule = new AlarmRule();
        rule.setRuleName("新名称");
        Result<Void> r = controller.update(5L, rule);
        assertEquals(200, r.getCode());
        verify(alarmRuleService, times(1)).update(eq(5L), eq(rule));
    }

    @Test
    @DisplayName("toggle 调用 service.toggle")
    void toggle_callsService() {
        Result<Void> r = controller.toggle(5L, 0);
        assertEquals(200, r.getCode());
        verify(alarmRuleService, times(1)).toggle(eq(5L), eq(0));
    }

    @Test
    @DisplayName("test 调用 service.test")
    void test_callsService() {
        Result<Void> r = controller.test(5L);
        assertEquals(200, r.getCode());
        verify(alarmRuleService, times(1)).test(5L);
    }

    @Test
    @DisplayName("target-options 调用 service.targetOptions（T11 评估对象候选）")
    void targetOptions_callsService() {
        when(alarmRuleService.targetOptions("API")).thenReturn(Collections.emptyList());
        Result<List<com.gatekeeper.dto.AlarmTargetVo>> r = controller.targetOptions("API");
        assertEquals(200, r.getCode());
        verify(alarmRuleService, times(1)).targetOptions("API");
    }

    @Test
    @DisplayName("7 个接口全部存在（端到端路由核查）")
    void allEndpointsExist() throws NoSuchMethodException {
        Class<?> c = AlarmRuleController.class;
        assertNotNull(c.getMethod("list", Integer.class));
        assertNotNull(c.getMethod("detail", Long.class));
        assertNotNull(c.getMethod("create", AlarmRule.class));
        assertNotNull(c.getMethod("update", Long.class, AlarmRule.class));
        assertNotNull(c.getMethod("toggle", Long.class, Integer.class));
        assertNotNull(c.getMethod("test", Long.class));
        // T11 新增：评估对象候选
        assertNotNull(c.getMethod("targetOptions", String.class));
    }

    private static void assertTrue(boolean b) {
        assertEquals(true, b);
    }
}
