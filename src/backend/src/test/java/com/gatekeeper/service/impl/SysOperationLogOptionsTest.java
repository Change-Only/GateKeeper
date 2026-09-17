package com.gatekeeper.service.impl;

import com.gatekeeper.dto.OperationLogOptionsVo;
import com.gatekeeper.mapper.SysOperationLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计筛选候选项（{@code GET /system/operation-log/filter-options}）单测。
 *
 * <p><b>背景</b>：这两个下拉此前由前端硬编码，与后端实际值域失同步 ——
 * 2026-09-17 实测库里 {@code operation_module} 真实有 <b>18</b> 个值，前端写死的 5 项
 * 只覆盖 4 个（14 个模块的记录"能看到、筛不出"）；而写死的
 * {@code LOGIN}/{@code LOGOUT} 两个"操作类型"**永远不会被写入**
 * （{@code OperationLogAspect} 只产出 CREATE/UPDATE/DELETE，且登录已被其排除）。</p>
 *
 * <p>本测试覆盖服务层的**纯归一化逻辑**（去重/剔空/语义序）；"值真的从库里查出来"
 * 由端到端探针在真库上验（单测把 mapper mock 掉了，证不了 SQL）。</p>
 *
 * <p>写法沿用项目既有约定：{@code ServiceImpl.baseMapper} 是 protected 字段，反射注入 mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("审计筛选候选项 归一化逻辑")
class SysOperationLogOptionsTest {

    @Mock
    private SysOperationLogMapper sysOperationLogMapper;

    private SysOperationLogServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SysOperationLogServiceImpl();
        Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                .getDeclaredField("baseMapper");
        baseMapperField.setAccessible(true);
        baseMapperField.set(service, sysOperationLogMapper);
        assertNotNull(ReflectionTestUtils.getField(service, "baseMapper"));
    }

    private void stub(List<String> modules, List<String> types) {
        when(sysOperationLogMapper.selectDistinctModules()).thenReturn(modules);
        when(sysOperationLogMapper.selectDistinctTypes()).thenReturn(types);
    }

    @Test
    @DisplayName("🔴 传的是库里的真实值域：18 个模块值原样下发（不再被截成 5 项）")
    void returnsAllRealModulesFromDb() {
        List<String> realModules = Arrays.asList(
                "ALERT", "API-CHANGE-LOG", "API-ENV-CONFIG", "API-GROUP-ENCRYPTION",
                "API-GROUP-ENV-CONFIG", "API-PARAM", "API-VERSION", "APP", "APP-CREDENTIAL",
                "AUTH", "CONFIG", "ENCRYPTION", "ENV", "GATEWAY", "GROUP", "INTERFACE",
                "PERMISSION", "SYS");
        stub(realModules, Arrays.asList("CREATE", "DELETE", "UPDATE"));

        OperationLogOptionsVo vo = service.filterOptions();

        assertEquals(18, vo.getModules().size(), "库里有 18 个模块值就必须回 18 个");
        assertTrue(vo.getModules().contains("GROUP"), "GROUP 必须在（旧前端列表漏了它）");
        assertTrue(vo.getModules().contains("ENCRYPTION"), "ENCRYPTION 必须在（旧前端列表漏了它）");
        assertTrue(vo.getModules().contains("API-VERSION"), "非 MODULE_MAP 的首段回退值也要在");
        assertEquals(realModules, vo.getModules(), "模块保持库返回序（SQL 已 ORDER BY）");
    }

    @Test
    @DisplayName("操作类型按 CREATE → UPDATE → DELETE 的语义序重排（不按库的字典序）")
    void typesUseSemanticOrder() {
        // 库按字典序返回 CREATE / DELETE / UPDATE
        stub(Collections.singletonList("APP"), Arrays.asList("CREATE", "DELETE", "UPDATE"));

        OperationLogOptionsVo vo = service.filterOptions();

        assertEquals(Arrays.asList("CREATE", "UPDATE", "DELETE"), vo.getTypes());
    }

    @Test
    @DisplayName("未知操作类型不丢：追加在语义序之后，且保持确定性顺序")
    void unknownTypesAppendedNotDropped() {
        stub(Collections.singletonList("APP"), Arrays.asList("LOGIN", "CREATE", "ZZZ", "DELETE"));

        OperationLogOptionsVo vo = service.filterOptions();

        assertEquals(Arrays.asList("CREATE", "DELETE", "LOGIN", "ZZZ"), vo.getTypes(),
                "未知类型（LOGIN/ZZZ）必须追加在尾部并按字典序，绝不能静默丢弃");
    }

    @Test
    @DisplayName("去重 + 剔 null/空串/纯空白，且 trim 首尾空白（防下拉出现两个一样的项）")
    void normalizesDuplicatesBlanksAndWhitespace() {
        stub(Arrays.asList("APP", "APP", null, "", "   ", " GROUP ", "GROUP"),
                Arrays.asList("CREATE", " CREATE ", null, "", "UPDATE"));

        OperationLogOptionsVo vo = service.filterOptions();

        assertEquals(Arrays.asList("APP", "GROUP"), vo.getModules());
        assertEquals(Arrays.asList("CREATE", "UPDATE"), vo.getTypes());
    }

    @Test
    @DisplayName("库返回 null（异常/无结果）时两字段仍为非 null 空列表，不抛 NPE")
    void nullFromMapperYieldsEmptyLists() {
        stub(null, null);

        OperationLogOptionsVo vo = service.filterOptions();

        assertNotNull(vo.getModules());
        assertNotNull(vo.getTypes());
        assertTrue(vo.getModules().isEmpty());
        assertTrue(vo.getTypes().isEmpty());
    }

    @Test
    @DisplayName("空表（有库无数据）：回空列表而非 null，前端据此回退本地兜底常量")
    void emptyTableYieldsEmptyLists() {
        stub(Collections.emptyList(), Collections.emptyList());

        OperationLogOptionsVo vo = service.filterOptions();

        assertEquals(0, vo.getModules().size());
        assertEquals(0, vo.getTypes().size());
    }

    @Test
    @DisplayName("两个 DISTINCT 查询各被调用一次（不做 N+1 循环查库）")
    void queriesEachDistinctOnce() {
        stub(Collections.singletonList("APP"), Collections.singletonList("CREATE"));

        service.filterOptions();

        verify(sysOperationLogMapper, times(1)).selectDistinctModules();
        verify(sysOperationLogMapper, times(1)).selectDistinctTypes();
    }

    @Test
    @DisplayName("返回顺序不受同一次调用内重复调用影响（无共享可变状态）")
    void idempotentAcrossCalls() {
        stub(Arrays.asList("SYS", "APP"), Arrays.asList("DELETE", "CREATE"));

        List<String> first = service.filterOptions().getModules();
        List<String> second = service.filterOptions().getModules();

        assertEquals(first, second);
        assertEquals(Arrays.asList("SYS", "APP"), second, "第二次调用不得被第一次的排序污染");
    }

    @Test
    @DisplayName("固定枚举取反：VO 不含写死的 LOGIN/LOGOUT（它们永不写入审计表）")
    void doesNotInventTypesNotPresentInDb() {
        stub(Collections.singletonList("APP"), Arrays.asList("CREATE", "UPDATE", "DELETE"));

        OperationLogOptionsVo vo = service.filterOptions();

        assertTrue(!vo.getTypes().contains("LOGIN"), "LOGIN 不在库里就不该出现在候选里");
        assertTrue(!vo.getTypes().contains("LOGOUT"), "LOGOUT 不在库里就不该出现在候选里");
    }
}
