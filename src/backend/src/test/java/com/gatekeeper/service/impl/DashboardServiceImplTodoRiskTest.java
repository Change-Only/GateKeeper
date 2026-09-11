package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.mapper.AlertMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.mapper.AppCredentialMapper;
import com.gatekeeper.security.banner.IpBanService;
import com.gatekeeper.service.CallLogService;
import com.gatekeeper.service.SecurityEventService;
import com.gatekeeper.vo.RiskVo;
import com.gatekeeper.vo.TodoItemVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DashboardService 概览页「待办 / 风险」聚合单测（T06-A）
 *
 * <p>覆盖：
 * <ul>
 *   <li>todo 固定 4 项、顺序、label/route/desc 契约；</li>
 *   <li>4 类待办各自的筛选口径与边界（NULL 到期时间、非启用密钥状态、已处理/已忽略告警均不计入）；</li>
 *   <li>计数通过 mapper.selectCount 下推 SQL（断言生成的 WHERE 条件），而非全表 size()；</li>
 *   <li>risk 三档分级正确。</li>
 * </ul>
 *
 * <p><strong>实现细节</strong>：MyBatis-Plus 的 {@code paramNameValuePairs} 采用<strong>惰性填充</strong>——
 * 条件参数在实际拼接 SQL（首次调用 {@code getSqlSegment()}）时才写入 Map。故断言参数值前必须经由
 * {@link #params(QueryWrapper)} 先触发一次 SQL 段生成，否则会读到空 Map。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DashboardService 概览待办/风险聚合 (T06-A)")
class DashboardServiceImplTodoRiskTest {

    @Mock
    private CallLogService callLogService;
    @Mock
    private IpBanService ipBanService;
    @Mock
    private SecurityEventService securityEventService;
    @Mock
    private ApiInterfaceMapper apiInterfaceMapper;
    @Mock
    private AppApiGrantMapper appApiGrantMapper;
    @Mock
    private AppCredentialMapper appCredentialMapper;
    @Mock
    private AlertMapper alertMapper;

    private DashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DashboardServiceImpl(callLogService, ipBanService, securityEventService,
                apiInterfaceMapper, appApiGrantMapper, appCredentialMapper, alertMapper);
    }

    /** 通用桩：4 个 mapper 的计数。 */
    private void stubCounts(long api, long grant, long cred, long alarm) {
        when(apiInterfaceMapper.selectCount(any())).thenReturn(api);
        when(appApiGrantMapper.selectCount(any())).thenReturn(grant);
        when(appCredentialMapper.selectCount(any())).thenReturn(cred);
        when(alertMapper.selectCount(any())).thenReturn(alarm);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<QueryWrapper> captor() {
        return ArgumentCaptor.forClass(QueryWrapper.class);
    }

    /**
     * 读取 wrapper 的 SQL 段并返回其参数映射（先触发 MP 惰性填充，再取 Map，保证顺序无关）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> params(QueryWrapper<?> wrapper) {
        wrapper.getSqlSegment(); // 触发 MyBatis-Plus 参数惰性填充
        return wrapper.getParamNameValuePairs();
    }

    @Test
    @DisplayName("todo：固定 4 项、顺序为 api/grant/cred/alarm，label/route/desc 符合契约")
    void todo_returnsFourItemsInFixedOrder() {
        stubCounts(0L, 0L, 0L, 1L);

        List<TodoItemVo> list = service.todo();

        assertEquals(4, list.size());
        assertEquals("api", list.get(0).getType());
        assertEquals("grant", list.get(1).getType());
        assertEquals("cred", list.get(2).getType());
        assertEquals("alarm", list.get(3).getType());

        assertEquals("待审核接口", list.get(0).getLabel());
        assertEquals("待审批授权", list.get(1).getLabel());
        assertEquals("密钥即将过期", list.get(2).getLabel());
        assertEquals("未处理告警", list.get(3).getLabel());

        assertEquals("/api/api-list", list.get(0).getRoute());
        assertEquals("/perm/perm-matrix", list.get(1).getRoute());
        assertEquals("/app", list.get(2).getRoute());
        assertEquals("/mon/mon-alarm", list.get(3).getRoute());

        assertEquals(0L, list.get(0).getCount());
        assertEquals(1L, list.get(3).getCount());
        assertNotNull(list.get(0).getDesc());
    }

    @Test
    @DisplayName("todo.api：SQL 条件 publish_status=1（待审核），计数取自 selectCount")
    void todo_apiCountsPendingReviewOnly() {
        stubCounts(5L, 0L, 0L, 0L);

        List<TodoItemVo> list = service.todo();

        assertEquals(5L, list.get(0).getCount());
        ArgumentCaptor<QueryWrapper> cap = captor();
        verify(apiInterfaceMapper, times(1)).selectCount(cap.capture());
        QueryWrapper<?> w = cap.getValue();
        assertTrue(w.getSqlSegment().toUpperCase().contains("PUBLISH_STATUS"),
                "应下推 publish_status 条件到 SQL");
        assertTrue(params(w).values().contains(1),
                "publish_status 取值应为 1（待审核）");
    }

    @Test
    @DisplayName("todo.grant：SQL 条件 status=0（待审批）")
    void todo_grantCountsPendingApprovalOnly() {
        stubCounts(0L, 3L, 0L, 0L);

        List<TodoItemVo> list = service.todo();

        assertEquals(3L, list.get(1).getCount());
        ArgumentCaptor<QueryWrapper> cap = captor();
        verify(appApiGrantMapper, times(1)).selectCount(cap.capture());
        QueryWrapper<?> w = cap.getValue();
        assertTrue(w.getSqlSegment().toUpperCase().contains("STATUS"));
        assertTrue(params(w).values().contains(0),
                "授权状态取值应为 0（待审批）");
    }

    @Test
    @DisplayName("todo.cred：仅启用中(status=1)且 expire_time 落在 (now, now+30d]；NULL 与非启用状态不计入")
    void todo_credExcludesNullExpireTimeAndNonEnabledStatus() {
        stubCounts(0L, 0L, 2L, 0L);

        List<TodoItemVo> list = service.todo();

        assertEquals(2L, list.get(2).getCount());
        ArgumentCaptor<QueryWrapper> cap = captor();
        verify(appCredentialMapper, times(1)).selectCount(cap.capture());
        QueryWrapper<?> w = cap.getValue();
        String seg = w.getSqlSegment().toUpperCase();

        assertTrue(seg.contains("EXPIRE_TIME"), "应包含 expire_time 条件");
        assertTrue(seg.contains("IS NOT NULL"), "expire_time 为 NULL 的密钥不应计入");
        assertTrue(seg.contains(">"), "应包含 expire_time > now 下界");
        assertTrue(seg.contains("<="), "应包含 expire_time <= now+30d 上界");

        // 仅 status=1（启用中）计入；2=已停用 / 3=已吊销 / 4=已过期 均不计入
        Map<String, Object> p = params(w);
        assertTrue(p.values().contains(1));
        assertFalse(p.values().contains(2));
        assertFalse(p.values().contains(3));
        assertFalse(p.values().contains(4));
    }

    @Test
    @DisplayName("todo.alarm：仅 status IN (0,1)（未读/已读未处理）；已处理(2)/已忽略(3) 不计入")
    void todo_alarmCountsUnhandledOnly() {
        stubCounts(0L, 0L, 0L, 1L);

        List<TodoItemVo> list = service.todo();

        assertEquals(1L, list.get(3).getCount());
        ArgumentCaptor<QueryWrapper> cap = captor();
        verify(alertMapper, times(1)).selectCount(cap.capture());
        QueryWrapper<?> w = cap.getValue();
        String seg = w.getSqlSegment().toUpperCase();
        assertTrue(seg.contains("STATUS"));
        assertTrue(seg.contains("IN"));

        Map<String, Object> p = params(w);
        assertTrue(p.values().contains(0), "未读(0)应计入");
        assertTrue(p.values().contains(1), "已读未处理(1)应计入");
        assertFalse(p.values().contains(2), "已处理(2)不应计入");
        assertFalse(p.values().contains(3), "已忽略(3)不应计入");
    }

    @Test
    @DisplayName("risk：CRITICAL/WARNING/INFO 三档分级正确，且均限定未处理告警")
    void risk_threeTiers() {
        // 调用顺序固定：high(CRITICAL) → mid(WARNING) → low(INFO)
        when(alertMapper.selectCount(any())).thenReturn(1L, 2L, 3L);

        RiskVo vo = service.risk();

        assertEquals(1L, vo.getHigh());
        assertEquals(2L, vo.getMid());
        assertEquals(3L, vo.getLow());

        ArgumentCaptor<QueryWrapper> cap = captor();
        verify(alertMapper, times(3)).selectCount(cap.capture());
        List<QueryWrapper> wrappers = cap.getAllValues();

        // 先在循环里取 SQL 段（同时触发参数填充），再做值断言，避免依赖调用顺序
        for (QueryWrapper<?> w : wrappers) {
            String seg = w.getSqlSegment().toUpperCase();
            assertTrue(seg.contains("LEVEL"));
            assertTrue(seg.contains("STATUS"));
        }
        assertTrue(params(wrappers.get(0)).values().contains("CRITICAL"));
        assertTrue(params(wrappers.get(1)).values().contains("WARNING"));
        assertTrue(params(wrappers.get(2)).values().contains("INFO"));
        for (QueryWrapper<?> w : wrappers) {
            Map<String, Object> p = params(w);
            assertTrue(p.values().contains(0));
            assertTrue(p.values().contains(1));
        }
    }

    @Test
    @DisplayName("todo：各域计数为 0 时正常返回（不臆造非零值）")
    void todo_returnsZeroCountsWhenNoPendingItems() {
        stubCounts(0L, 0L, 0L, 0L);

        List<TodoItemVo> list = service.todo();

        assertEquals(4, list.size());
        for (TodoItemVo item : list) {
            assertEquals(0L, item.getCount());
        }
    }
}
