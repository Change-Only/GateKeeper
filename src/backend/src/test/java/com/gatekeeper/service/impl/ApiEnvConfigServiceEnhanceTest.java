package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.ApiEnvConfigDto;
import com.gatekeeper.entity.ApiEnvConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import com.gatekeeper.util.UpstreamProber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiEnvConfigServiceImpl T03b 增强能力单测 —— UPSERT / 地址校验 / 连通性测试状态机。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiEnvConfigService upsert/test/configStatus")
class ApiEnvConfigServiceEnhanceTest {

    @Mock
    private ApiEnvConfigMapper apiEnvConfigMapper;
    @Mock
    private UpstreamProber upstreamProber;

    private ApiEnvConfigServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ApiEnvConfigServiceImpl();
        Field bm = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class.getDeclaredField("baseMapper");
        bm.setAccessible(true);
        bm.set(service, apiEnvConfigMapper);
        Field prober = ApiEnvConfigServiceImpl.class.getDeclaredField("upstreamProber");
        prober.setAccessible(true);
        prober.set(service, upstreamProber);
    }

    private ApiEnvConfigDto dto(Long apiId, String envCode, String url) {
        ApiEnvConfigDto d = new ApiEnvConfigDto();
        d.setApiId(apiId);
        d.setEnvCode(envCode);
        d.setUpstreamUrl(url);
        return d;
    }

    @Test
    @DisplayName("upsert 未命中 → 插入，configStatus=1")
    void upsert_insertsWhenAbsent() {
        when(apiEnvConfigMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(apiEnvConfigMapper.insert(any(ApiEnvConfig.class))).thenReturn(1);

        ApiEnvConfigDto r = service.upsert(dto(1L, "dev", "http://10.0.0.1:8080"));

        assertEquals(Integer.valueOf(1), r.getConfigStatus());
        verify(apiEnvConfigMapper, times(1)).insert(any(ApiEnvConfig.class));
        verify(apiEnvConfigMapper, times(0)).updateById(any(ApiEnvConfig.class));
    }

    @Test
    @DisplayName("upsert 命中 → 更新，地址变更重置 configStatus=1")
    void upsert_updatesWhenPresent() {
        ApiEnvConfig existing = new ApiEnvConfig();
        existing.setId(5L);
        existing.setApiId(1L);
        existing.setEnvCode("dev");
        existing.setUpstreamUrl("http://old:8080");
        existing.setConfigStatus(2); // 之前已验证
        when(apiEnvConfigMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
        when(apiEnvConfigMapper.updateById(any(ApiEnvConfig.class))).thenReturn(1);

        ApiEnvConfigDto r = service.upsert(dto(1L, "dev", "http://new:9090"));

        assertEquals("http://new:9090", r.getUpstreamUrl());
        assertEquals(Integer.valueOf(1), r.getConfigStatus()); // 地址变更 → 重置为已配置
        verify(apiEnvConfigMapper, times(1)).updateById(any(ApiEnvConfig.class));
        verify(apiEnvConfigMapper, times(0)).insert(any(ApiEnvConfig.class));
    }

    @Test
    @DisplayName("upsert 地址非法（非 http(s)）→ 400")
    void upsert_rejectsInvalidUrl() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.upsert(dto(1L, "dev", "ftp://nope")));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("testConnectivity 通过 → configStatus=2（已验证）")
    void testConnectivity_verified() {
        ApiEnvConfig e = new ApiEnvConfig();
        e.setId(7L);
        e.setApiId(1L);
        e.setEnvCode("dev");
        e.setUpstreamUrl("http://10.0.0.1:8080");
        e.setConfigStatus(1);
        when(apiEnvConfigMapper.selectById(7L)).thenReturn(e);
        when(apiEnvConfigMapper.updateById(any(ApiEnvConfig.class))).thenReturn(1);
        when(upstreamProber.probe(anyString(), anyInt())).thenReturn(true);

        ApiEnvConfigDto r = service.testConnectivity(7L);
        assertEquals(Integer.valueOf(2), r.getConfigStatus());
        assertEquals(1L, r.getApiId());
    }

    @Test
    @DisplayName("testConnectivity 失败 → configStatus=1（已配置未验证）")
    void testConnectivity_notVerified() {
        ApiEnvConfig e = new ApiEnvConfig();
        e.setId(8L);
        e.setApiId(1L);
        e.setEnvCode("pre");
        e.setUpstreamUrl("http://10.0.0.2:8080");
        e.setConfigStatus(2); // 曾验证过
        when(apiEnvConfigMapper.selectById(8L)).thenReturn(e);
        when(apiEnvConfigMapper.updateById(any(ApiEnvConfig.class))).thenReturn(1);
        when(upstreamProber.probe(anyString(), anyInt())).thenReturn(false);

        ApiEnvConfigDto r = service.testConnectivity(8L);
        assertEquals(Integer.valueOf(1), r.getConfigStatus());
    }

    @Test
    @DisplayName("testConnectivity 配置不存在 → 404")
    void testConnectivity_notFound() {
        when(apiEnvConfigMapper.selectById(404L)).thenReturn(null);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.testConnectivity(404L));
        assertEquals(404, ex.getCode());
    }
}
