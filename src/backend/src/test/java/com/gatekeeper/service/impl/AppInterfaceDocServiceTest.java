package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.entity.App;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.ApiParamMapper;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.mapper.AppMapper;
import com.gatekeeper.vo.AppInterfaceDocVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AppInterfaceDocServiceImpl 单元测试 — T16-2
 *
 * <p><b>最关键的一条：过滤必须发生在 SQL 里，而不是内存里。</b>
 * 「只导出有权限的接口」的判定条件是「授权 status=1 + 在有效期内」，
 * 这两条都在 {@code QueryWrapper} 里 —— 用 Mockito 桩掉 mapper 后，
 * <b>内存逻辑再怎么写错都测不出来</b>。所以本测试专门捕获那个 Wrapper，
 * 断言它的 SQL 片段与参数值里确实含有 {@code app_id} / {@code status} /
 * {@code valid_from} / {@code valid_to} 以及 status=1。</p>
 *
 * <p>第二组重点是<b>被剔除的两类要分开计数</b>：
 * 授权指向的接口「已删除」（dangling）与「已停用」（disabled）语义不同，
 * 合成一个数字会让「导出 3 条、授权 5 条」无法解释。</p>
 *
 * <p>第三组是渲染前的数据组织：分组名回填、参数按 paramType 排序、
 * 清单按「分组名 → 接口路径」稳定排序。</p>
 *
 * @author GateKeeper
 * @since T16-2 (2026-09-15)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AppInterfaceDocService：只导有权限的接口 / 悬空与停用分开计数 / 稳定排序")
class AppInterfaceDocServiceTest {

    @Mock
    private AppMapper appMapper;
    @Mock
    private AppApiGrantMapper grantMapper;
    @Mock
    private ApiInterfaceMapper apiInterfaceMapper;
    @Mock
    private ApiGroupMapper apiGroupMapper;
    @Mock
    private ApiParamMapper apiParamMapper;
    /** T17：可见性判定（本类用例与加密无关，stub 成「保护关闭」） */
    @Mock
    private com.gatekeeper.service.InterfaceVisibilityService interfaceVisibilityService;

    private AppInterfaceDocServiceImpl service;

    private static final long APP_ID = 14L;

    @BeforeEach
    void setUp() {
        // T17：末尾追加 interfaceCryptoService / interfaceVisibilityService
        service = new AppInterfaceDocServiceImpl(
                appMapper, grantMapper, apiInterfaceMapper, apiGroupMapper, apiParamMapper,
                new com.gatekeeper.support.PassthroughInterfaceCrypto(), interfaceVisibilityService);
        when(interfaceVisibilityService.resolveViewer())
                .thenReturn(com.gatekeeper.security.InterfaceViewer.unprotected());
    }

    // ===================== 造数据的小工具 =====================

    private App app() {
        App a = new App();
        a.setId(APP_ID);
        a.setAppName("订单中心");
        a.setAppKey("ak_prod_demo");
        a.setStatus(1);
        return a;
    }

    private AppApiGrant grant(Long grantId, Long apiId, String env) {
        AppApiGrant g = new AppApiGrant();
        g.setId(grantId);
        g.setAppId(APP_ID);
        g.setApiId(apiId);
        g.setEnvCode(env);
        g.setStatus(1);
        g.setQpsLimit(100);
        g.setDailyQuota(10000L);
        return g;
    }

    private ApiInterface iface(Long id, String name, String path, Long groupId, Integer status) {
        ApiInterface i = new ApiInterface();
        i.setId(id);
        i.setApiCode("code." + id);
        i.setInterfaceName(name);
        i.setInterfacePath(path);
        i.setRequestMethod("POST");
        i.setRequestParamType("JSON");
        i.setDescription(name + " 的描述");
        i.setGroupId(groupId);
        i.setStatus(status);
        i.setCurrentVersion("v1");
        i.setAuthRequired(1);
        i.setTimeoutMs(3000);
        return i;
    }

    private ApiGroup group(Long id, String name) {
        ApiGroup g = new ApiGroup();
        g.setId(id);
        g.setGroupName(name);
        return g;
    }

    private ApiParam param(Long id, Long apiId, int paramType, String field, Integer sortOrder) {
        ApiParam p = new ApiParam();
        p.setId(id);
        p.setApiId(apiId);
        p.setParamType(paramType);
        p.setFieldName(field);
        p.setFieldType("string");
        p.setRequired(1);
        p.setSortOrder(sortOrder);
        return p;
    }

    // ===================== 入参与应用存在性 =====================

    @Test
    @DisplayName("build：appId 为空 ⇒ 400")
    void rejectsNullAppId() {
        assertThrows(GatewayException.class, () -> service.build(null));
    }

    @Test
    @DisplayName("build：应用不存在 ⇒ 400（而不是返回空文档让人以为没权限）")
    void rejectsMissingApp() {
        when(appMapper.selectById(APP_ID)).thenReturn(null);
        assertThrows(GatewayException.class, () -> service.build(APP_ID));
    }

    @Test
    @DisplayName("build：无授权 ⇒ 空清单，total=0，且不触发接口/参数查询")
    void emptyWhenNoGrant() {
        when(appMapper.selectById(APP_ID)).thenReturn(app());
        when(grantMapper.selectList(any())).thenReturn(Collections.emptyList());

        AppInterfaceDocVo vo = service.build(APP_ID);

        assertEquals(0, vo.getTotal().intValue());
        assertTrue(vo.getItems().isEmpty());
        assertEquals("订单中心", vo.getAppName());
        assertEquals("ak_prod_demo", vo.getAppKey());
    }

    // ===================== 🔴 SQL 级过滤 =====================

    @Test
    @DisplayName("🔴 build：授权查询必须带 app_id + status=1 + 有效期区间（过滤必须落在 SQL 里）")
    @SuppressWarnings("unchecked")
    void grantQueryCarriesAllFilters() {
        when(appMapper.selectById(APP_ID)).thenReturn(app());
        when(grantMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.build(APP_ID);

        ArgumentCaptor<Wrapper<AppApiGrant>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(grantMapper).selectList(cap.capture());
        QueryWrapper<AppApiGrant> qw = (QueryWrapper<AppApiGrant>) cap.getValue();

        // 注意顺序：getSqlSegment() 会填充 paramNameValuePairs，必须先调它
        String sql = qw.getSqlSegment();
        Map<String, Object> vals = qw.getParamNameValuePairs();

        assertTrue(sql.contains("app_id"), "必须按应用过滤，实际 SQL: " + sql);
        assertTrue(sql.contains("status"), "必须按授权状态过滤，实际 SQL: " + sql);
        assertTrue(vals.containsValue(1), "必须只取 status=1（已生效）；待审批/已驳回/已过期/已撤销都要被排除");
        assertTrue(vals.containsValue(APP_ID), "应用 ID 必须作为参数下推");
        assertTrue(sql.contains("valid_from"), "必须校验生效日期，实际 SQL: " + sql);
        assertTrue(sql.contains("valid_to"), "必须校验失效日期，实际 SQL: " + sql);
        assertTrue(vals.containsValue(LocalDate.now()),
                "有效期比较基准必须是「今天」，否则过期授权会被导出");
    }

    // ===================== 悬空 / 停用 分开计数 =====================

    @Test
    @DisplayName("build：授权指向的接口已删除 ⇒ 计入 danglingCount，不进清单")
    void danglingGrantCounted() {
        when(appMapper.selectById(APP_ID)).thenReturn(app());
        when(grantMapper.selectList(any())).thenReturn(Collections.singletonList(grant(1L, 999L, "prod")));
        when(apiInterfaceMapper.selectList(any())).thenReturn(Collections.emptyList());

        AppInterfaceDocVo vo = service.build(APP_ID);

        assertEquals(0, vo.getTotal().intValue());
        assertEquals(1, vo.getDanglingCount().intValue(), "接口记录不存在应算悬空");
        assertEquals(0, vo.getDisabledCount().intValue());
    }

    @Test
    @DisplayName("build：接口存在但已停用 ⇒ 计入 disabledCount（与悬空区分开）")
    void disabledInterfaceCounted() {
        when(appMapper.selectById(APP_ID)).thenReturn(app());
        when(grantMapper.selectList(any())).thenReturn(Collections.singletonList(grant(1L, 26L, "prod")));
        when(apiInterfaceMapper.selectList(any()))
                .thenReturn(Collections.singletonList(iface(26L, "已停用接口", "/gateway/x", 1L, 0)));

        AppInterfaceDocVo vo = service.build(APP_ID);

        assertEquals(0, vo.getTotal().intValue());
        assertEquals(0, vo.getDanglingCount().intValue());
        assertEquals(1, vo.getDisabledCount().intValue(), "接口停用与「接口不存在」是两回事，必须分开计");
    }

    @Test
    @DisplayName("build：apiId 为空的脏授权 ⇒ 计入悬空，不能 NPE")
    void nullApiIdTreatedAsDangling() {
        when(appMapper.selectById(APP_ID)).thenReturn(app());
        when(grantMapper.selectList(any())).thenReturn(Collections.singletonList(grant(1L, null, "prod")));

        AppInterfaceDocVo vo = service.build(APP_ID);

        assertEquals(1, vo.getDanglingCount().intValue());
        assertEquals(0, vo.getTotal().intValue());
    }

    // ===================== 正常导出 =====================

    @Test
    @DisplayName("build：接口启用 ⇒ 进清单，字段与分组名回填完整")
    void exportsEnabledInterface() {
        when(appMapper.selectById(APP_ID)).thenReturn(app());
        when(grantMapper.selectList(any())).thenReturn(Collections.singletonList(grant(11L, 26L, "prod")));
        when(apiInterfaceMapper.selectList(any()))
                .thenReturn(Collections.singletonList(iface(26L, "创建订单", "/gateway/order/create", 3L, 1)));
        when(apiGroupMapper.selectList(any()))
                .thenReturn(Collections.singletonList(group(3L, "订单域")));
        when(apiParamMapper.selectList(any())).thenReturn(Collections.emptyList());

        AppInterfaceDocVo vo = service.build(APP_ID);

        assertEquals(1, vo.getTotal().intValue());
        AppInterfaceDocVo.Item it = vo.getItems().get(0);
        assertEquals("创建订单", it.getInterfaceName());
        assertEquals("/gateway/order/create", it.getInterfacePath());
        assertEquals("订单域", it.getGroupName(), "分组名必须回填（文档里没有 groupId 只有分组名）");
        assertEquals("prod", it.getEnvCode());
        assertEquals(Long.valueOf(11L), it.getGrantId());
    }

    @Test
    @DisplayName("build：同一接口在多环境各有一条授权 ⇒ 各出一行（环境是授权维度）")
    void sameInterfaceInTwoEnvsProducesTwoRows() {
        when(appMapper.selectById(APP_ID)).thenReturn(app());
        when(grantMapper.selectList(any())).thenReturn(Arrays.asList(
                grant(11L, 26L, "prod"), grant(12L, 26L, "test")));
        when(apiInterfaceMapper.selectList(any()))
                .thenReturn(Collections.singletonList(iface(26L, "创建订单", "/gateway/order/create", 3L, 1)));
        when(apiGroupMapper.selectList(any())).thenReturn(Collections.singletonList(group(3L, "订单域")));
        when(apiParamMapper.selectList(any())).thenReturn(Collections.emptyList());

        AppInterfaceDocVo vo = service.build(APP_ID);

        assertEquals(2, vo.getTotal().intValue(), "同一接口在两个环境各有一条授权，应各占一行");
        List<String> envs = vo.getItems().stream()
                .map(AppInterfaceDocVo.Item::getEnvCode).collect(Collectors.toList());
        assertTrue(envs.contains("prod") && envs.contains("test"));
    }

    @Test
    @DisplayName("build：参数按 paramType 再 sortOrder 排序（文档章节顺序）")
    void paramsSortedByTypeThenOrder() {
        when(appMapper.selectById(APP_ID)).thenReturn(app());
        when(grantMapper.selectList(any())).thenReturn(Collections.singletonList(grant(11L, 26L, "prod")));
        when(apiInterfaceMapper.selectList(any()))
                .thenReturn(Collections.singletonList(iface(26L, "创建订单", "/gateway/order/create", 3L, 1)));
        when(apiGroupMapper.selectList(any())).thenReturn(Collections.singletonList(group(3L, "订单域")));

        List<ApiParam> raw = new ArrayList<>(Arrays.asList(
                param(5L, 26L, 4, "respId", 1),      // RESPONSE
                param(4L, 26L, 3, "bodyB", 2),       // BODY
                param(3L, 26L, 3, "bodyA", 1),       // BODY
                param(1L, 26L, 1, "X-App-Key", 1)    // HEADER
        ));
        when(apiParamMapper.selectList(any())).thenReturn(raw);

        AppInterfaceDocVo vo = service.build(APP_ID);
        List<ApiParam> got = vo.getItems().get(0).getParams();

        assertEquals(4, got.size());
        assertEquals("X-App-Key", got.get(0).getFieldName(), "HEADER(1) 必须排在最先");
        assertEquals("bodyA", got.get(1).getFieldName(), "同类型内按 sortOrder 升序");
        assertEquals("bodyB", got.get(2).getFieldName());
        assertEquals("respId", got.get(3).getFieldName(), "RESPONSE(4) 最后");
    }

    @Test
    @DisplayName("build：清单按「分组名 → 接口路径」稳定排序")
    void itemsSortedByGroupThenPath() {
        when(appMapper.selectById(APP_ID)).thenReturn(app());
        when(grantMapper.selectList(any())).thenReturn(Arrays.asList(
                grant(1L, 30L, "prod"), grant(2L, 31L, "prod"), grant(3L, 32L, "prod")));
        when(apiInterfaceMapper.selectList(any())).thenReturn(Arrays.asList(
                iface(31L, "b", "/gateway/b", 2L, 1),
                iface(32L, "c", "/gateway/c", 1L, 1),
                iface(30L, "a", "/gateway/a", 1L, 1)));
        when(apiGroupMapper.selectList(any())).thenReturn(Arrays.asList(
                group(1L, "A 分组"), group(2L, "B 分组")));
        when(apiParamMapper.selectList(any())).thenReturn(Collections.emptyList());

        AppInterfaceDocVo vo = service.build(APP_ID);
        List<String> paths = vo.getItems().stream()
                .map(AppInterfaceDocVo.Item::getInterfacePath).collect(Collectors.toList());

        assertEquals(Arrays.asList("/gateway/a", "/gateway/c", "/gateway/b"), paths,
                "先按分组名（A 分组 < B 分组），同组内按路径");
    }

    @Test
    @DisplayName("build：分组已被删除 ⇒ 分组名留空而不是报错（不能让文档导出整体失败）")
    void missingGroupLeavesNameNull() {
        when(appMapper.selectById(APP_ID)).thenReturn(app());
        when(grantMapper.selectList(any())).thenReturn(Collections.singletonList(grant(11L, 26L, "prod")));
        when(apiInterfaceMapper.selectList(any()))
                .thenReturn(Collections.singletonList(iface(26L, "孤儿接口", "/gateway/x", 99L, 1)));
        when(apiGroupMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(apiParamMapper.selectList(any())).thenReturn(Collections.emptyList());

        AppInterfaceDocVo vo = service.build(APP_ID);

        assertEquals(1, vo.getTotal().intValue());
        assertEquals(null, vo.getItems().get(0).getGroupName());
        assertNotNull(vo.getItems().get(0).getParams(), "params 不能为 null，前端要直接遍历");
    }
}
