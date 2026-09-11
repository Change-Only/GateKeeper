package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiVersion;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiVersionServiceImpl T03b 增强能力单测 —— 版本发布（含已验证环境校验）与灰度设置。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiVersionService publish/gray")
class ApiVersionServicePublishTest {

    @Mock
    private ApiVersionMapper apiVersionMapper;
    @Mock
    private ApiEnvConfigMapper apiEnvConfigMapper;
    @Mock
    private ApiInterfaceMapper apiInterfaceMapper;

    private ApiVersionServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ApiVersionServiceImpl();
        setField("baseMapper", apiVersionMapper,
                com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class);
        setField("apiEnvConfigMapper", apiEnvConfigMapper, ApiVersionServiceImpl.class);
        setField("apiInterfaceMapper", apiInterfaceMapper, ApiVersionServiceImpl.class);
    }

    private void setField(String name, Object value, Class<?> owner) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private ApiVersion version(Long id, Long apiId, String v, int isCurrent) {
        ApiVersion e = new ApiVersion();
        e.setId(id);
        e.setApiId(apiId);
        e.setVersion(v);
        e.setIsCurrent(isCurrent);
        e.setStatus(1);
        e.setGrayRatio(0);
        return e;
    }

    @Test
    @DisplayName("publish 成功：置 current、同步接口 publishStatus=2 与 currentVersion")
    void publish_success() {
        when(apiVersionMapper.selectById(10L)).thenReturn(version(10L, 1L, "v2", 0));
        when(apiEnvConfigMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(apiVersionMapper.update(any(ApiVersion.class), any(QueryWrapper.class))).thenReturn(1);
        when(apiVersionMapper.updateById(any(ApiVersion.class))).thenReturn(1);
        when(apiInterfaceMapper.updateById(any(ApiInterface.class))).thenReturn(1);

        ApiVersionDto dto = service.publish(10L);

        assertEquals(Integer.valueOf(1), dto.getIsCurrent());
        assertEquals("v2", dto.getVersion());
        assertEquals(1L, dto.getApiId());
        // 其余版本清零
        verify(apiVersionMapper, times(1)).update(any(ApiVersion.class), any(QueryWrapper.class));
        // 同步 api_interface
        ArgumentCaptor<ApiInterface> ifaceCaptor = ArgumentCaptor.forClass(ApiInterface.class);
        verify(apiInterfaceMapper, times(1)).updateById(ifaceCaptor.capture());
        assertEquals(Integer.valueOf(2), ifaceCaptor.getValue().getPublishStatus());
        assertEquals("v2", ifaceCaptor.getValue().getCurrentVersion());
    }

    @Test
    @DisplayName("publish 无已验证环境 → 400，且不更新任何状态")
    void publish_requiresVerifiedEnv() {
        when(apiVersionMapper.selectById(10L)).thenReturn(version(10L, 1L, "v2", 0));
        when(apiEnvConfigMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        GatewayException ex = assertThrows(GatewayException.class, () -> service.publish(10L));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("已验证"));
        verify(apiVersionMapper, never()).updateById(any(ApiVersion.class));
        verify(apiInterfaceMapper, never()).updateById(any(ApiInterface.class));
    }

    @Test
    @DisplayName("publish 版本不存在 → 404")
    void publish_notFound() {
        when(apiVersionMapper.selectById(99L)).thenReturn(null);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.publish(99L));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("setGray 成功：当前版本可设置 0-100")
    void setGray_success() {
        when(apiVersionMapper.selectById(10L)).thenReturn(version(10L, 1L, "v2", 1));
        when(apiVersionMapper.updateById(any(ApiVersion.class))).thenReturn(1);

        ApiVersionDto dto = service.setGray(10L, 30);
        assertEquals(Integer.valueOf(30), dto.getGrayRatio());
        verify(apiVersionMapper, times(1)).updateById(any(ApiVersion.class));
    }

    @Test
    @DisplayName("setGray 非当前版本 → 400")
    void setGray_rejectNonCurrent() {
        when(apiVersionMapper.selectById(10L)).thenReturn(version(10L, 1L, "v1", 0));
        GatewayException ex = assertThrows(GatewayException.class, () -> service.setGray(10L, 30));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("setGray 比例越界 → 400")
    void setGray_rejectOutOfRange() {
        GatewayException ex1 = assertThrows(GatewayException.class, () -> service.setGray(10L, 101));
        assertEquals(400, ex1.getCode());
        GatewayException ex2 = assertThrows(GatewayException.class, () -> service.setGray(10L, -1));
        assertEquals(400, ex2.getCode());
        GatewayException ex3 = assertThrows(GatewayException.class, () -> service.setGray(10L, null));
        assertEquals(400, ex3.getCode());
    }

    @Test
    @DisplayName("setGray 版本不存在 → 404")
    void setGray_notFound() {
        when(apiVersionMapper.selectById(eq(5L))).thenReturn(null);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.setGray(5L, 10));
        assertEquals(404, ex.getCode());
    }
}
