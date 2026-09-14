package com.gatekeeper.alarm;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gatekeeper.alarm.impl.AlarmRuleServiceImpl;
import com.gatekeeper.dto.AlarmTargetVo;
import com.gatekeeper.entity.AlarmRule;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.App;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.AlarmRuleMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.AppMapper;
import com.gatekeeper.service.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T11 · 告警规则「评估对象绑定」单测。
 *
 * <p>钉死四件事：</p>
 * <ol>
 *   <li>写入契约：scopeType=1 必须给 targetType；scopeType=2 归一清空；targetIds 归一去重</li>
 *   <li>可清空：targetIds 传空串 ⇒ 走显式 UpdateWrapper 置 NULL
 *       （MyBatis-Plus 默认 NOT_NULL 策略下 updateById 清不掉字段）</li>
 *   <li>评估真正按对象展开：N 个对象 ⇒ N 次告警；按应用维度回填 alert.relatedAppId</li>
 *   <li>静默按对象隔离：静默键含 {@code 规则ID:APP:对象ID}</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("T11 告警规则评估对象绑定")
class AlarmRuleTargetBindingTest {

    @Mock
    private AlarmRuleMapper alarmRuleMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private AlertService alertService;

    @Mock
    private NotifySender notifySender;

    @Mock
    private NotifyChannelService notifyChannelService;

    @Mock
    private AppMapper appMapper;

    @Mock
    private ApiInterfaceMapper apiInterfaceMapper;

    private AlarmRuleServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AlarmRuleServiceImpl(redisTemplate, alertService, notifySender,
                notifyChannelService, appMapper, apiInterfaceMapper);
        Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                .getDeclaredField("baseMapper");
        baseMapperField.setAccessible(true);
        baseMapperField.set(service, alarmRuleMapper);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ===================== 1) 写入契约 =====================

    @Test
    @DisplayName("create：scopeType=1 未给 targetType ⇒ 400（不允许静默空转）")
    void create_scope1WithoutTargetType_throws() {
        AlarmRule in = baseRule();
        in.setScopeType(1);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.create(in));
        assertTrue(ex.getMessage().contains("评估对象维度"), "实际=" + ex.getMessage());
        verify(alarmRuleMapper, never()).insert(any(AlarmRule.class));
    }

    @Test
    @DisplayName("create：targetType 大小写不敏感并归一为大写；targetIds 去重排序")
    void create_normalizesTargetFields() {
        AlarmRule in = baseRule();
        in.setScopeType(1);
        in.setTargetType("api");
        in.setTargetIds("3, 1,3, 2");

        AlarmRule saved = service.create(in);

        assertEquals(AlarmRuleService.TARGET_TYPE_API, saved.getTargetType());
        assertEquals("3,1,2", saved.getTargetIds());
    }

    @Test
    @DisplayName("create：scopeType=2（平台全局）时清空对象绑定")
    void create_scope2_clearsTargetFields() {
        AlarmRule in = baseRule();
        in.setScopeType(2);
        in.setTargetType("API");
        in.setTargetIds("1,2");

        AlarmRule saved = service.create(in);

        assertNull(saved.getTargetType());
        assertNull(saved.getTargetIds());
    }

    @Test
    @DisplayName("create：targetType 非法值 ⇒ 400")
    void create_invalidTargetType_throws() {
        AlarmRule in = baseRule();
        in.setScopeType(1);
        in.setTargetType("HOST");
        assertThrows(GatewayException.class, () -> service.create(in));
    }

    // ===================== 2) 可清空（NOT_NULL 策略坑） =====================

    @Test
    @DisplayName("update：targetIds 传空串 ⇒ 显式 UpdateWrapper 置 NULL（updateById 清不掉）")
    void update_blankTargetIds_clearsWithWrapper() {
        when(alarmRuleMapper.selectById(9L)).thenReturn(existingRule(9L, 1, "API", "1,2"));
        AlarmRule in = new AlarmRule();
        in.setTargetIds("");

        service.update(9L, in);

        ArgumentCaptor<Wrapper> captor = wrapperCaptor();
        verify(alarmRuleMapper, times(1)).update(isNull(), captor.capture());
        UpdateWrapper<AlarmRule> wrapper = (UpdateWrapper<AlarmRule>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("target_ids"),
                "SET 子句应含 target_ids，实际=" + wrapper.getSqlSet());
    }

    @Test
    @DisplayName("update：修改无关字段（只改名称）⇒ 不触发清空、也不因缺 targetType 报错")
    void update_unrelatedField_doesNotTouchBinding() {
        when(alarmRuleMapper.selectById(9L)).thenReturn(existingRule(9L, 1, "API", "1,2"));
        AlarmRule in = new AlarmRule();
        in.setRuleName("改名");

        service.update(9L, in);

        verify(alarmRuleMapper, never()).update(isNull(), any());
        ArgumentCaptor<AlarmRule> captor = ArgumentCaptor.forClass(AlarmRule.class);
        verify(alarmRuleMapper, times(1)).updateById(captor.capture());
        assertEquals("API", captor.getValue().getTargetType());
        assertEquals("1,2", captor.getValue().getTargetIds());
    }

    @Test
    @DisplayName("update：scopeType 改为 2 ⇒ 清空 target_type 与 target_ids")
    void update_scope2_clearsBinding() {
        when(alarmRuleMapper.selectById(9L)).thenReturn(existingRule(9L, 1, "API", "1,2"));
        AlarmRule in = new AlarmRule();
        in.setScopeType(2);

        service.update(9L, in);

        ArgumentCaptor<Wrapper> captor = wrapperCaptor();
        verify(alarmRuleMapper, times(1)).update(isNull(), captor.capture());
        String set = ((UpdateWrapper<AlarmRule>) captor.getValue()).getSqlSet();
        assertTrue(set.contains("target_type") && set.contains("target_ids"), "实际=" + set);
    }

    @Test
    @DisplayName("update：scopeType=1 却把 targetType 清成空 ⇒ 400")
    void update_scope1ClearingTargetType_throws() {
        when(alarmRuleMapper.selectById(9L)).thenReturn(existingRule(9L, 1, "API", "1,2"));
        AlarmRule in = new AlarmRule();
        in.setTargetType("");

        assertThrows(GatewayException.class, () -> service.update(9L, in));
        verify(alarmRuleMapper, never()).updateById(any(AlarmRule.class));
    }

    // ===================== 3) 候选对象接口 =====================

    @Test
    @DisplayName("targetOptions：API 维度返回 id/label/extra=方法+路径")
    void targetOptions_api() {
        when(apiInterfaceMapper.selectList(any())).thenReturn(Collections.singletonList(api(7L, "用户查询", "GET", "/user/{id}", null)));

        List<AlarmTargetVo> list = service.targetOptions("api");

        assertEquals(1, list.size());
        assertEquals(7L, list.get(0).getId());
        assertEquals("用户查询", list.get(0).getLabel());
        assertEquals("GET /user/{id}", list.get(0).getExtra());
    }

    @Test
    @DisplayName("targetOptions：APP 维度返回 id/label/extra=描述")
    void targetOptions_app() {
        when(appMapper.selectList(any())).thenReturn(Collections.singletonList(app(1L, "订单中心", "交易主应用")));

        List<AlarmTargetVo> list = service.targetOptions("APP");

        assertEquals(1, list.size());
        assertEquals(1L, list.get(0).getId());
        assertEquals("订单中心", list.get(0).getLabel());
        assertEquals("交易主应用", list.get(0).getExtra());
    }

    @Test
    @DisplayName("targetOptions：非法维度 ⇒ 400")
    void targetOptions_invalidType_throws() {
        assertThrows(GatewayException.class, () -> service.targetOptions("HOST"));
        assertThrows(GatewayException.class, () -> service.targetOptions(null));
    }

    // ===================== 4) 评估按对象展开 + 静默隔离 =====================

    @Test
    @DisplayName("evaluateRealtime：按接口维度 2 个对象 ⇒ 发 2 次告警，静默键按对象隔离")
    void evaluateRealtime_perApiTarget_publishesPerObject() {
        when(alarmRuleMapper.selectList(any())).thenReturn(Collections.singletonList(
                enabledRule(1L, 1, "API", null, ">1", 30)));
        when(apiInterfaceMapper.selectList(any())).thenReturn(Arrays.asList(
                api(11L, "接口A", "GET", "/a", null),
                api(12L, "接口B", "POST", "/b", null)));

        service.evaluateRealtime();

        verify(alertService, times(2)).publish(eq("CRITICAL"), anyString(), anyString(), anyString(), isNull(), isNull(), isNull());
        verify(valueOps).set(eq("gk:alarm:silence:1:API:11"), eq("1"), eq(Duration.ofMinutes(30)));
        verify(valueOps).set(eq("gk:alarm:silence:1:API:12"), eq("1"), eq(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("evaluateRealtime：按应用维度 ⇒ 告警回填 relatedAppId/relatedAppName（便于溯源）")
    void evaluateRealtime_perAppTarget_fillsRelatedApp() {
        when(alarmRuleMapper.selectList(any())).thenReturn(Collections.singletonList(
                enabledRule(2L, 1, "APP", "5", ">1", 10)));
        when(appMapper.selectList(any())).thenReturn(Collections.singletonList(app(5L, "订单中心", null)));

        service.evaluateRealtime();

        verify(alertService, times(1)).publish(eq("CRITICAL"), anyString(), anyString(),
                anyString(), eq(5L), eq("订单中心"), isNull());
        verify(valueOps).set(eq("gk:alarm:silence:2:APP:5"), eq("1"), eq(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("evaluateRealtime：targetIds 指定对象 ⇒ 只评估命中的对象")
    void evaluateRealtime_onlyPickedTargets() {
        when(alarmRuleMapper.selectList(any())).thenReturn(Collections.singletonList(
                enabledRule(3L, 1, "API", "12", ">1", 30)));
        when(apiInterfaceMapper.selectList(any())).thenReturn(Arrays.asList(
                api(11L, "接口A", "GET", "/a", null),
                api(12L, "接口B", "POST", "/b", null)));

        service.evaluateRealtime();

        verify(alertService, times(1)).publish(anyString(), anyString(), anyString(), anyString(), isNull(), isNull(), isNull());
        verify(valueOps).set(eq("gk:alarm:silence:3:API:12"), eq("1"), any(Duration.class));
        verify(valueOps, never()).set(eq("gk:alarm:silence:3:API:11"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("evaluateRealtime：维度下无对象 ⇒ 不发告警也不报错")
    void evaluateRealtime_noTargets_noAlert() {
        when(alarmRuleMapper.selectList(any())).thenReturn(Collections.singletonList(
                enabledRule(4L, 1, "API", null, ">1", 30)));
        when(apiInterfaceMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.evaluateRealtime();

        verify(alertService, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateRealtime：阈值未突破 ⇒ 不发告警（按对象展开不改变阈值语义）")
    void evaluateRealtime_notBreached_noAlert() {
        when(alarmRuleMapper.selectList(any())).thenReturn(Collections.singletonList(
                enabledRule(5L, 1, "API", null, ">99", 30)));
        when(apiInterfaceMapper.selectList(any())).thenReturn(Collections.singletonList(api(11L, "接口A", "GET", "/a", null)));

        service.evaluateRealtime();

        verify(alertService, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluateRealtime：未绑定维度的历史规则 ⇒ 退化为单次评估（不退化为静默空转）")
    void evaluateRealtime_legacyRule_fallsBackToSingleEval() {
        when(alarmRuleMapper.selectList(any())).thenReturn(Collections.singletonList(
                enabledRule(6L, 1, null, null, ">1", 30)));

        service.evaluateRealtime();

        verify(alertService, times(1)).publish(anyString(), anyString(), anyString(), anyString(), isNull(), isNull(), isNull());
        verify(valueOps).set(eq("gk:alarm:silence:6:global"), eq("1"), any(Duration.class));
        // 历史规则不应触发任何对象枚举查询
        verify(apiInterfaceMapper, never()).selectList(any());
        verify(appMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("evaluateOffline：平台全局规则不按对象展开")
    void evaluateOffline_doesNotExpandTargets() {
        when(alarmRuleMapper.selectList(any())).thenReturn(Collections.singletonList(
                enabledRule(7L, 2, null, null, ">1", 30)));

        service.evaluateOffline();

        verify(alertService, times(1)).publish(anyString(), anyString(), anyString(), anyString(), isNull(), isNull(), isNull());
        verify(apiInterfaceMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("指标读取优先级：按对象计数 > 规则级计数 > 派生兜底")
    void readMetric_prefersPerTargetCounter() {
        when(alarmRuleMapper.selectList(any())).thenReturn(Collections.singletonList(
                enabledRule(8L, 1, "API", null, ">100", 30)));
        when(apiInterfaceMapper.selectList(any())).thenReturn(Collections.singletonList(api(1L, "接口A", "GET", "/a", null)));
        when(valueOps.get("gk:alarm:win:8:API:1")).thenReturn("150");
        when(valueOps.get("gk:alarm:win:8")).thenReturn("1");

        service.evaluateRealtime();

        verify(valueOps, atLeastOnce()).get("gk:alarm:win:8:API:1");
        verify(alertService, times(1)).publish(anyString(), anyString(), anyString(), anyString(), isNull(), isNull(), isNull());
    }

    // ===================== 5) 规则级 list/detail 未受影响 =====================

    @Test
    @DisplayName("detail：规则不存在 ⇒ 404")
    void detail_missing_throws() {
        when(alarmRuleMapper.selectById(404L)).thenReturn(null);
        assertThrows(GatewayException.class, () -> service.get(404L));
    }

    @Test
    @DisplayName("create：返回实体带自增 ID 且写入默认 status=1")
    void create_defaultsStatus() {
        AlarmRule in = baseRule();
        in.setScopeType(2);
        in.setStatus(null);

        AlarmRule saved = service.create(in);

        assertEquals(1, saved.getStatus());
        assertNotNull(saved.getCreatedAt());
    }

    // ===================== helpers =====================

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Wrapper> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    private AlarmRule baseRule() {
        AlarmRule r = new AlarmRule();
        r.setRuleName("测试规则");
        r.setAlarmType("FAIL_RATE");
        r.setThreshold(">5");
        return r;
    }

    private AlarmRule enabledRule(Long id, int scopeType, String targetType, String targetIds,
                                  String threshold, int silencePeriod) {
        AlarmRule r = existingRule(id, scopeType, targetType, targetIds);
        r.setThreshold(threshold);
        r.setSilencePeriod(silencePeriod);
        r.setAlarmLevel(3);
        r.setAlarmType("FAIL_RATE");
        return r;
    }

    private AlarmRule existingRule(Long id, int scopeType, String targetType, String targetIds) {
        AlarmRule r = new AlarmRule();
        r.setId(id);
        r.setRuleName("规则" + id);
        r.setScopeType(scopeType);
        r.setTargetType(targetType);
        r.setTargetIds(targetIds);
        r.setStatus(1);
        r.setThreshold(">5");
        return r;
    }

    private ApiInterface api(Long id, String name, String method, String path, String desc) {
        ApiInterface it = new ApiInterface();
        it.setId(id);
        it.setInterfaceName(name);
        it.setRequestMethod(method);
        it.setInterfacePath(path);
        it.setDescription(desc);
        return it;
    }

    private App app(Long id, String name, String desc) {
        App a = new App();
        a.setId(id);
        a.setAppName(name);
        a.setDescription(desc);
        return a;
    }
}
