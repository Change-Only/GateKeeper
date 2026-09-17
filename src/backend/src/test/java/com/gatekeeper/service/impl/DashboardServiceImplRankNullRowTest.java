package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gatekeeper.entity.ApiCallLog;
import com.gatekeeper.mapper.AlertMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.mapper.AppCredentialMapper;
import com.gatekeeper.security.banner.IpBanService;
import com.gatekeeper.service.CallLogService;
import com.gatekeeper.service.SecurityEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DashboardService 当日「应用 / 接口调用排行」的 <strong>null 行</strong>回归测试。
 *
 * <h3>被验缺陷（既有，非本轮引入）</h3>
 * <p>{@code appRank()} / {@code interfaceRank()} 用
 * {@code QueryWrapper.select("app_id","app_name")} 之类<strong>只选部分列且不选主键</strong>的投影，
 * 再对结果做 for-each。而 MyBatis 的 {@code returnInstanceForEmptyRow} <strong>默认为 false</strong>：
 * 当某行<strong>所选列全部为 NULL</strong> 时，{@code selectList} 往 List 里放的是
 * <strong>null 元素</strong>而不是"空实体"。对 null 解引用即 NPE
 * （线上实测 {@code java.lang.NullPointerException} 落点在循环体首行），
 * 于是两个端点恒返回 500。</p>
 *
 * <p>触发数据真实存在：网关在<strong>未解析出应用/接口</strong>时也会写 api_call_log，
 * 这类行 {@code app_id}/{@code app_name}/{@code interface_id}/{@code interface_path} 全为 NULL，
 * 正好构成"整行所选列全 NULL"。</p>
 *
 * <h3>可证伪性</h3>
 * <p>本类每个用例都往列表里塞了 {@code null} 元素。把 {@code DashboardServiceImpl} 里
 * 的 {@code if (log == null) continue;} 去掉，这些用例<strong>立刻抛 NPE 而失败</strong>——
 * 即本类确实在守护那两行守卫，而非"平凡通过"。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DashboardService 调用排行 · null 行回归")
class DashboardServiceImplRankNullRowTest {

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

    /** 桩：让 callLogService.list(...) 返回给定列表（模拟 MyBatis 含 null 元素的结果）。 */
    private void stubLogs(List<ApiCallLog> logs) {
        when(callLogService.list(any(Wrapper.class))).thenReturn(logs);
    }

    private static ApiCallLog log(Long appId, String appName, Long interfaceId, String interfacePath) {
        ApiCallLog l = new ApiCallLog();
        l.setAppId(appId);
        l.setAppName(appName);
        l.setInterfaceId(interfaceId);
        l.setInterfacePath(interfacePath);
        return l;
    }

    /** 从排行结果里按名字取 callCount。 */
    private static long countOf(List<Map<String, Object>> rank, String key, String name) {
        for (Map<String, Object> item : rank) {
            if (name.equals(item.get(key))) {
                return ((Number) item.get("callCount")).longValue();
            }
        }
        return -1L;
    }

    // ==================================================================
    // appRank
    // ==================================================================

    @Test
    @DisplayName("appRank：混入 null 行不抛 NPE，且 null 行不参与统计")
    void appRank_skipsNullRowsAndStillRanks() {
        stubLogs(Arrays.asList(
                null,                                                   // 「整行全 NULL」的形态
                log(1L, "Alpha", null, null),                            // 修复前：本行即 NPE 触发点
                log(2L, "Beta", null, null),
                null,
                log(1L, "Alpha", null, null)));

        List<Map<String, Object>> rank = service.appRank();

        assertNotNull(rank);
        assertEquals(2, rank.size(), "null 行既不报错也不计入，只留 Alpha/Beta 两项");
        assertEquals(2L, countOf(rank, "appName", "Alpha"));
        assertEquals(1L, countOf(rank, "appName", "Beta"));
        assertEquals(1L, ((Number) rank.get(0).get("appId")).longValue(), "Alpha 应带出其 appId");
    }

    @Test
    @DisplayName("appRank：结果全为 null 行时返回空列表（不抛异常）")
    void appRank_allNullRowsReturnsEmpty() {
        stubLogs(Arrays.asList(null, null, null));

        List<Map<String, Object>> rank = service.appRank();

        assertNotNull(rank);
        assertTrue(rank.isEmpty());
    }

    @Test
    @DisplayName("appRank：app_name 为 NULL 但 app_id 有值时，仍归入「未知」（既有语义保留）")
    void appRank_nullNameWithIdFallsBackToUnknown() {
        stubLogs(Collections.singletonList(log(7L, null, null, null)));

        List<Map<String, Object>> rank = service.appRank();

        assertEquals(1, rank.size());
        assertEquals("未知", rank.get(0).get("appName"));
        assertEquals(7L, ((Number) rank.get(0).get("appId")).longValue());
    }

    @Test
    @DisplayName("appRank：按调用量降序且最多 10 项")
    void appRank_descOrderAndTop10() {
        List<ApiCallLog> logs = new ArrayList<>();
        logs.add(null); // 顺带再压一次 null 守卫
        // 造 12 个应用，调用量分别为 1..12
        for (int i = 1; i <= 12; i++) {
            for (int j = 0; j < i; j++) {
                logs.add(log((long) i, "App" + i, null, null));
            }
        }
        stubLogs(logs);

        List<Map<String, Object>> rank = service.appRank();

        assertEquals(10, rank.size(), "Top 10 截断");
        assertEquals("App12", rank.get(0).get("appName"), "调用量最大者排第一");
        assertEquals(12L, countOf(rank, "appName", "App12"));
        assertEquals("App3", rank.get(9).get("appName"), "第 10 名应为 App3（12..3）");
        assertFalse(countOf(rank, "appName", "App1") > 0, "App1/App2 被挤出 Top 10");
    }

    // ==================================================================
    // interfaceRank
    // ==================================================================

    @Test
    @DisplayName("interfaceRank：混入 null 行不抛 NPE，且 null 行不参与统计")
    void interfaceRank_skipsNullRowsAndStillRanks() {
        stubLogs(Arrays.asList(
                null,
                log(null, null, 11L, "/order/query"),
                log(null, null, 12L, "/user/get"),
                null,
                log(null, null, 11L, "/order/query")));

        List<Map<String, Object>> rank = service.interfaceRank();

        assertNotNull(rank);
        assertEquals(2, rank.size());
        assertEquals(2L, countOf(rank, "interfacePath", "/order/query"));
        assertEquals(1L, countOf(rank, "interfacePath", "/user/get"));
        assertEquals("/order/query", rank.get(0).get("interfacePath"), "调用量高者排前");
    }

    @Test
    @DisplayName("interfaceRank：结果全为 null 行时返回空列表（不抛异常）")
    void interfaceRank_allNullRowsReturnsEmpty() {
        stubLogs(Arrays.asList(null, null));

        List<Map<String, Object>> rank = service.interfaceRank();

        assertNotNull(rank);
        assertTrue(rank.isEmpty());
    }

    @Test
    @DisplayName("interfaceRank：interface_path 为 NULL 但 interface_id 有值时，归入「未知」")
    void interfaceRank_nullPathFallsBackToUnknown() {
        stubLogs(Collections.singletonList(log(null, null, 33L, null)));

        List<Map<String, Object>> rank = service.interfaceRank();

        assertEquals(1, rank.size());
        assertEquals("未知", rank.get(0).get("interfacePath"));
    }
}
