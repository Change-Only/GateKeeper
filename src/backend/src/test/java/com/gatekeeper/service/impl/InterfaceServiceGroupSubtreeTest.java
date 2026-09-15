package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.mapper.ApiChangeLogMapper;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.ApiParamMapper;
import com.gatekeeper.mapper.ApiVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
 * InterfaceService 分组筛选「含全部子孙分组」单测。
 *
 * <p>需求：接口列表按「所属分组」筛选时，<strong>选中父分组应看到它下面全部层级的接口</strong>，
 * 而不是只看到直接挂在父分组上的接口。实现上把 groupId 展开成「自身 + 全部子孙分组」的 ID 集合，
 * 再以 IN 条件下推 SQL。</p>
 *
 * <p>覆盖：
 * <ul>
 *   <li>groupId 为 null 时不追加任何分组条件；</li>
 *   <li>根分组 → 递归展开 3 层（含孙分组）；</li>
 *   <li>中间分组 → 展开其后代；</li>
 *   <li>叶子分组 → 退化为 {@code eq}，保持既有 SQL 形状（刻意的优化，见下）；</li>
 *   <li>分组条件与接口名模糊条件叠加；</li>
 *   <li>分组表存在数据环路时不会死递归；</li>
 *   <li>{@link InterfaceServiceImpl#pageQuery} 与 {@link InterfaceServiceImpl#pageQueryEnriched} 两端语义一致。</li>
 * </ul></p>
 *
 * <p><strong>实现细节</strong>：MyBatis-Plus 的 {@code paramNameValuePairs} 采用<strong>惰性填充</strong>——
 * 条件参数在实际拼接 SQL（首次调用 {@code getSqlSegment()}）时才写入 Map。故断言参数值前必须经由
 * {@link #params(QueryWrapper)} 先触发一次 SQL 段生成，否则会读到空 Map。</p>
 *
 * <p><strong>为何断言 eq/IN 的形态差异</strong>：当展开集合只有自身 1 个元素时退化为 {@code eq}
 * 是<strong>刻意选择</strong>（避免无谓改动既有执行计划），属需要被锁定的契约，故在此显式断言，
 * 防止后续重构无意改变 SQL 形状。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InterfaceService 分组筛选含全部子孙分组")
class InterfaceServiceGroupSubtreeTest {

    @Mock
    private ApiGroupMapper apiGroupMapper;
    @Mock
    private ApiInterfaceMapper apiInterfaceMapper;
    @Mock
    private ApiParamMapper apiParamMapper;
    @Mock
    private ApiVersionMapper apiVersionMapper;
    @Mock
    private ApiEnvConfigMapper apiEnvConfigMapper;
    @Mock
    private ApiChangeLogMapper apiChangeLogMapper;

    private InterfaceServiceImpl service;

    /** parentId -> 子分组列表，模拟 api_group 表。 */
    private Map<Long, List<ApiGroup>> children;

    @BeforeEach
    void setUp() {
        // 构造参数顺序 == @RequiredArgsConstructor 收集的 final 字段声明顺序
        service = new InterfaceServiceImpl(apiGroupMapper, apiParamMapper,
                apiVersionMapper, apiEnvConfigMapper, apiChangeLogMapper);
        // baseMapper 由 Spring 在 ServiceImpl 中注入，单测用反射直塞
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(service, apiInterfaceMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        children = new HashMap<>();
        // 默认分组树：1(配网) ├─ 2(核心指标) ├─ 4(子指标)  └─ 3(校核工具)
        children.put(1L, Arrays.asList(group(2L, 1L), group(3L, 1L)));
        children.put(2L, Collections.singletonList(group(4L, 2L)));
        children.put(3L, Collections.emptyList());
        children.put(4L, Collections.emptyList());

        stubGroupTree();
        stubPage();
    }

    // =====================================================================
    // 基础设施
    // =====================================================================

    private static ApiGroup group(long id, Long parentId) {
        ApiGroup g = new ApiGroup();
        g.setId(id);
        g.setParentId(parentId);
        g.setGroupName("g" + id);
        return g;
    }

    /** 按 parent_id 回放 children 映射；顺带验证递归确实逐层查了表。 */
    @SuppressWarnings("unchecked")
    private void stubGroupTree() {
        when(apiGroupMapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> {
            QueryWrapper<?> w = inv.getArgument(0);
            w.getSqlSegment(); // 触发参数惰性填充，否则读不到 parent_id
            Object parentId = w.getParamNameValuePairs().values().stream().findFirst().orElse(null);
            List<ApiGroup> kids = children.get(parentId);
            return kids == null ? new ArrayList<ApiGroup>() : kids;
        });
    }

    private void stubPage() {
        when(apiInterfaceMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(new Page<ApiInterface>(1, 10));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<QueryWrapper> captor() {
        return ArgumentCaptor.forClass(QueryWrapper.class);
    }

    /** 读取 wrapper 的参数映射（先触发 MP 惰性填充，保证顺序无关）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> params(QueryWrapper<?> wrapper) {
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs();
    }

    /** 捕获 pageQueryEnriched 下推给 SQL 的 wrapper。 */
    @SuppressWarnings("unchecked")
    private QueryWrapper<ApiInterface> capturedWrapper() {
        ArgumentCaptor<QueryWrapper> cap = captor();
        verify(apiInterfaceMapper, times(1)).selectPage(any(Page.class), cap.capture());
        return cap.getValue();
    }

    private String sqlOf(QueryWrapper<?> w) {
        return w.getSqlSegment().toUpperCase();
    }

    // =====================================================================
    // 用例
    // =====================================================================

    @Test
    @DisplayName("groupId 为 null：不追加任何分组条件")
    void nullGroupId_addsNoCondition() {
        service.pageQueryEnriched(1, 10, null, null);

        QueryWrapper<ApiInterface> w = capturedWrapper();
        assertFalse(sqlOf(w).contains("GROUP_ID"), "未选分组时不应出现 group_id 条件");
    }

    @Test
    @DisplayName("根分组：展开全部子孙（1 → 1,2,3,4），SQL 用 IN")
    void rootGroup_expandsAllDescendants() {
        service.pageQueryEnriched(1, 10, null, 1L);

        QueryWrapper<ApiInterface> w = capturedWrapper();
        assertTrue(sqlOf(w).contains("GROUP_ID IN"), "父分组应下推 IN 条件");
        Map<String, Object> p = params(w);
        assertTrue(p.containsValue(1L), "含自身 1");
        assertTrue(p.containsValue(2L), "含子分组 2");
        assertTrue(p.containsValue(3L), "含子分组 3");
        assertTrue(p.containsValue(4L), "含孙分组 4（证明递归到第 3 层）");
        assertEquals(4, p.size(), "根分组应恰好展开 4 个 ID");
    }

    @Test
    @DisplayName("中间分组：展开其后代（2 → 2,4）")
    void middleGroup_expandsItsSubtree() {
        service.pageQueryEnriched(1, 10, null, 2L);

        QueryWrapper<ApiInterface> w = capturedWrapper();
        assertTrue(sqlOf(w).contains("GROUP_ID IN"));
        Map<String, Object> p = params(w);
        assertTrue(p.containsValue(2L));
        assertTrue(p.containsValue(4L));
        assertEquals(2, p.size(), "中间分组应恰好展开 2 个 ID，不含兄弟分组 3");
    }

    @Test
    @DisplayName("叶子分组：退化为 eq，保持既有 SQL 形状")
    void leafGroup_degradesToEq() {
        service.pageQueryEnriched(1, 10, null, 3L);

        QueryWrapper<ApiInterface> w = capturedWrapper();
        String sql = sqlOf(w);
        assertTrue(sql.contains("GROUP_ID"), "应出现 group_id 条件");
        assertFalse(sql.contains("GROUP_ID IN"), "叶子分组应退化 eq 而非 IN");
        assertEquals(Collections.singletonList(3L), new ArrayList<>(params(w).values()));
    }

    @Test
    @DisplayName("分组条件与接口名模糊条件叠加")
    void groupScope_combinesWithNameLike() {
        service.pageQueryEnriched(1, 10, "订单", 1L);

        QueryWrapper<ApiInterface> w = capturedWrapper();
        String sql = sqlOf(w);
        assertTrue(sql.contains("INTERFACE_NAME"), "应保留接口名模糊条件");
        assertTrue(sql.contains("GROUP_ID IN"), "应同时下推分组子树条件");
        assertEquals(5, params(w).size(), "1 个名称参数 + 4 个分组 ID");
    }

    @Test
    @DisplayName("分组表存在环路（5 → 6 → 5）时不死递归")
    void cyclicGroupData_terminates() {
        children.clear();
        children.put(5L, Collections.singletonList(group(6L, 5L)));
        children.put(6L, Collections.singletonList(group(5L, 6L)));

        PageResult<?> result = service.pageQueryEnriched(1, 10, null, 5L);

        assertNotNull(result, "环路数据下应正常返回而非栈溢出");
        QueryWrapper<ApiInterface> w = capturedWrapper();
        assertTrue(sqlOf(w).contains("GROUP_ID IN"));
        Map<String, Object> p = params(w);
        assertTrue(p.containsValue(5L));
        assertTrue(p.containsValue(6L));
        assertEquals(2, p.size(), "环路应被收敛为 2 个 ID");
    }

    @Test
    @DisplayName("pageQuery 与 pageQueryEnriched 分组语义一致（同样展开子孙）")
    void pageQuery_hasSameSubtreeSemantics() {
        service.pageQuery(1, 10, null, 1L);

        ArgumentCaptor<QueryWrapper> cap = captor();
        verify(apiInterfaceMapper, times(1)).selectPage(any(Page.class), cap.capture());
        QueryWrapper<?> w = cap.getValue();
        assertTrue(sqlOf(w).contains("GROUP_ID IN"));
        Map<String, Object> p = params(w);
        assertTrue(p.containsValue(1L));
        assertTrue(p.containsValue(2L));
        assertTrue(p.containsValue(3L));
        assertTrue(p.containsValue(4L));
    }

    @Test
    @DisplayName("pageQuery 叶子分组同样退化为 eq")
    void pageQuery_leafDegradesToEq() {
        service.pageQuery(1, 10, null, 4L);

        ArgumentCaptor<QueryWrapper> cap = captor();
        verify(apiInterfaceMapper, times(1)).selectPage(any(Page.class), cap.capture());
        QueryWrapper<?> w = cap.getValue();
        assertFalse(sqlOf(w).contains("GROUP_ID IN"));
        assertEquals(Collections.singletonList(4L), new ArrayList<>(params(w).values()));
    }
}
