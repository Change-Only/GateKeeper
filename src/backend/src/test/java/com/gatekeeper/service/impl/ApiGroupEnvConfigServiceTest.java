package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.ApiGroupEnvConfigDto;
import com.gatekeeper.entity.ApiGroupEnvConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.EnvConfigResolver;
import com.gatekeeper.gateway.dto.EffectiveEnvConfig;
import com.gatekeeper.mapper.ApiGroupEnvConfigMapper;
import com.gatekeeper.service.ApiGroupEnvConfigService;
import com.gatekeeper.util.UpstreamProber;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiGroupEnvConfigServiceImpl 单元测试 — T13 分组环境配置服务
 *
 * <p>重点覆盖「upsert 是幂等的」这件事：页面形态是「一个环境一行，填了就存」，
 * 若 upsert 只会 insert，用户每点一次确定就多一条配置，唯一键会直接报冲突；
 * 若只会 update，第一次配置永远存不进去。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiGroupEnvConfigService：upsert 幂等 / 校验 / 状态推导 / effective 共用解析器")
class ApiGroupEnvConfigServiceTest {

    @Mock
    private ApiGroupEnvConfigMapper mapper;
    @Mock
    private UpstreamProber upstreamProber;
    @Mock
    private EnvConfigResolver envConfigResolver;

    private ApiGroupEnvConfigServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ApiGroupEnvConfigServiceImpl();
        set(service, "baseMapper", mapper, com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class);
        set(service, "upstreamProber", upstreamProber, ApiGroupEnvConfigServiceImpl.class);
        set(service, "envConfigResolver", envConfigResolver, ApiGroupEnvConfigServiceImpl.class);
    }

    // ===================== upsert =====================

    @Test
    @DisplayName("upsert 未命中 → 插入，且 configStatus=1、mockStatus 缺省为 200")
    void upsertInsertsWhenAbsent() {
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(mapper.insert(any(ApiGroupEnvConfig.class))).thenReturn(1);

        ApiGroupEnvConfigDto dto = dto(2L, "prod", "http://order:8080");
        ApiGroupEnvConfigDto saved = service.upsert(dto);

        ArgumentCaptor<ApiGroupEnvConfig> cap = ArgumentCaptor.forClass(ApiGroupEnvConfig.class);
        verify(mapper, times(1)).insert(cap.capture());
        assertEquals(Integer.valueOf(1), cap.getValue().getConfigStatus());
        assertEquals(Integer.valueOf(200), cap.getValue().getMockStatus(), "Mock 状态码缺省必须是 200，不能是 0");
        assertEquals(Integer.valueOf(0), cap.getValue().getMockEnabled());
        assertEquals("http://order:8080", saved.getUpstreamUrl());
    }

    @Test
    @DisplayName("upsert 命中 → 更新同一条（不新增），地址变更后 configStatus 回落到 1 已配置")
    void upsertUpdatesWhenPresent() {
        ApiGroupEnvConfig existing = entity(8L, 2L, "prod", "http://old:8080");
        existing.setConfigStatus(2); // 之前已"验证过"
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
        when(mapper.updateById(any(ApiGroupEnvConfig.class))).thenReturn(1);

        service.upsert(dto(2L, "prod", "http://new:8080"));

        verify(mapper, times(0)).insert(any(ApiGroupEnvConfig.class));
        verify(mapper, times(1)).updateById(any(ApiGroupEnvConfig.class));
        assertEquals(Integer.valueOf(1), existing.getConfigStatus(),
                "换了地址，旧的『已验证』不能顺延到新地址");
        assertEquals("http://new:8080", existing.getUpstreamUrl());
    }

    @Test
    @DisplayName("upsert 地址未变时保留『已验证』状态（避免每次保存都掉档）")
    void upsertKeepsVerifiedWhenUrlUnchanged() {
        ApiGroupEnvConfig existing = entity(8L, 2L, "prod", "http://same:8080");
        existing.setConfigStatus(2);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        service.upsert(dto(2L, "prod", "http://same:8080"));

        assertEquals(Integer.valueOf(2), existing.getConfigStatus());
    }

    @Test
    @DisplayName("upsert 缺 groupId / envCode / upstreamUrl 一律 400")
    void upsertValidatesRequired() {
        assertEquals(400, assertThrows(GatewayException.class, () -> service.upsert(dto(null, "prod", "http://x"))).getCode());
        assertEquals(400, assertThrows(GatewayException.class, () -> service.upsert(dto(2L, null, "http://x"))).getCode());
        assertEquals(400, assertThrows(GatewayException.class, () -> service.upsert(dto(2L, "prod", "  "))).getCode());
    }

    @Test
    @DisplayName("服务前缀必须以 http(s):// 开头（防手写 host:port 落库）")
    void upsertRejectsBadScheme() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.upsert(dto(2L, "prod", "order-svc:8080")));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("http://"));
    }

    // ===================== toggleMock =====================

    @Test
    @DisplayName("toggleMock 0→1，并把 mockStatus 补成 200")
    void toggleMockFlipsAndFillsStatus() {
        ApiGroupEnvConfig e = entity(9L, 2L, "prod", "http://x:8080");
        e.setMockEnabled(0);
        e.setMockStatus(null);
        when(mapper.selectById(9L)).thenReturn(e);

        service.toggleMock(9L);

        assertEquals(Integer.valueOf(1), e.getMockEnabled());
        assertEquals(Integer.valueOf(200), e.getMockStatus());
        verify(mapper, times(1)).updateById(any(ApiGroupEnvConfig.class));
    }

    @Test
    @DisplayName("toggleMock 不存在的配置 → 404")
    void toggleMockNotFound() {
        when(mapper.selectById(404L)).thenReturn(null);
        assertEquals(404, assertThrows(GatewayException.class, () -> service.toggleMock(404L)).getCode());
    }

    // ===================== testConnectivity =====================

    @Test
    @DisplayName("连通性测试通过 → configStatus=2 已验证；失败 → 回落 1 已配置")
    void testConnectivitySetsStatus() {
        ApiGroupEnvConfig e = entity(9L, 2L, "prod", "http://x:8080");
        e.setConfigStatus(1);
        e.setConnectTimeout(1500);
        when(mapper.selectById(9L)).thenReturn(e);
        when(upstreamProber.probe("http://x:8080", 1500)).thenReturn(true);

        assertEquals(Integer.valueOf(2), service.testConnectivity(9L).getConfigStatus());

        when(upstreamProber.probe("http://x:8080", 1500)).thenReturn(false);
        assertEquals(Integer.valueOf(1), service.testConnectivity(9L).getConfigStatus());
    }

    // ===================== delete =====================

    @Test
    @DisplayName("delete 不存在的配置 → 404（不静默成功）")
    void deleteNotFound() {
        when(mapper.selectById(404L)).thenReturn(null);
        assertEquals(404, assertThrows(GatewayException.class, () -> service.delete(404L)).getCode());
    }

    // ===================== effective =====================

    @Test
    @DisplayName("effective 返回 4 个环境，且与网关共用 EnvConfigResolver（不自己走父链）")
    void effectiveUsesSharedResolver() {
        when(envConfigResolver.resolveForGroup(any(), anyString()))
                .thenReturn(EffectiveEnvConfig.fallback("x"));

        List<EffectiveEnvConfig> out = service.effective(2L);

        assertEquals(ApiGroupEnvConfigService.ENVS.size(), out.size());
        assertEquals(4, out.size());
        for (String env : ApiGroupEnvConfigService.ENVS) {
            verify(envConfigResolver, times(1)).resolveForGroup(2L, env);
        }
    }

    @Test
    @DisplayName("effective 缺 groupId → 400")
    void effectiveValidatesGroupId() {
        assertEquals(400, assertThrows(GatewayException.class, () -> service.effective(null)).getCode());
    }

    // ===================== fixtures =====================

    private ApiGroupEnvConfigDto dto(Long groupId, String env, String url) {
        ApiGroupEnvConfigDto d = new ApiGroupEnvConfigDto();
        d.setGroupId(groupId);
        d.setEnvCode(env);
        d.setUpstreamUrl(url);
        return d;
    }

    private ApiGroupEnvConfig entity(Long id, Long groupId, String env, String url) {
        ApiGroupEnvConfig c = new ApiGroupEnvConfig();
        c.setId(id);
        c.setGroupId(groupId);
        c.setEnvCode(env);
        c.setUpstreamUrl(url);
        c.setMockEnabled(0);
        c.setMockStatus(200);
        c.setConfigStatus(1);
        return c;
    }

    /** 反射注入（服务用字段注入 + ServiceImpl 的 baseMapper 在父类上） */
    private void set(Object target, String name, Object value, Class<?> owner) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
