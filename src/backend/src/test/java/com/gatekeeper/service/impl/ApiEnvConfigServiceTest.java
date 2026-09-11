package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.ApiEnvConfigDto;
import com.gatekeeper.entity.ApiEnvConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiEnvConfigServiceImpl 单元测试 — T03b 接口环境配置服务
 *
 * <p>覆盖 list / get / create / update / toggleMock 关键路径与校验：
 * 必填校验、configStatus 推导（upstreamUrl 决定 1/2）、toggleMock 翻转。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiEnvConfigService create/update/toggleMock/configStatus")
class ApiEnvConfigServiceTest {

    @Mock
    private ApiEnvConfigMapper apiEnvConfigMapper;

    private ApiEnvConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApiEnvConfigServiceImpl();
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(service, apiEnvConfigMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("create 必填缺失抛 400（upstreamUrl）")
    void create_missingUpstreamUrl() {
        ApiEnvConfigDto dto = new ApiEnvConfigDto();
        dto.setApiId(1L);
        dto.setEnvCode("prod");
        GatewayException ex = assertThrows(GatewayException.class, () -> service.create(dto));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("create 推导 configStatus=1（upstreamUrl 存在）")
    void create_derivesConfigStatusSet() {
        when(apiEnvConfigMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(apiEnvConfigMapper.insert(any(ApiEnvConfig.class))).thenReturn(1);

        ApiEnvConfigDto dto = new ApiEnvConfigDto();
        dto.setApiId(1L);
        dto.setEnvCode("prod");
        dto.setUpstreamUrl("http://svc");
        ApiEnvConfigDto result = service.create(dto);
        assertEquals(Integer.valueOf(1), result.getConfigStatus());
    }

    @Test
    @DisplayName("create 空白 upstreamUrl 视为缺失并抛 400（create 必填 upstreamUrl，configStatus 必为 1）")
    void create_blankUpstreamUrlRejected() {
        ApiEnvConfigDto dto = new ApiEnvConfigDto();
        dto.setApiId(1L);
        dto.setEnvCode("prod");
        dto.setUpstreamUrl(""); // 空白 → 必填校验失败
        GatewayException ex = assertThrows(GatewayException.class, () -> service.create(dto));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("update 重算 configStatus 并仅改可编辑字段")
    void update_recomputesConfigStatus() {
        ApiEnvConfig existing = sample(5L, 1L, "prod", "http://old", 1);
        when(apiEnvConfigMapper.selectById(5L)).thenReturn(existing);
        when(apiEnvConfigMapper.updateById(any(ApiEnvConfig.class))).thenReturn(1);

        ApiEnvConfigDto dto = new ApiEnvConfigDto();
        dto.setUpstreamUrl("http://new");
        dto.setMockEnabled(1);
        service.update(5L, dto);
        verify(apiEnvConfigMapper, times(1)).updateById(any(ApiEnvConfig.class));
    }

    @Test
    @DisplayName("toggleMock 翻转 mockEnabled 0↔1")
    void toggleMock_flips() {
        ApiEnvConfig e = sample(6L, 1L, "prod", "http://svc", 0);
        when(apiEnvConfigMapper.selectById(6L)).thenReturn(e);
        when(apiEnvConfigMapper.updateById(any(ApiEnvConfig.class))).thenReturn(1);

        service.toggleMock(6L);
        verify(apiEnvConfigMapper, times(1)).updateById(any(ApiEnvConfig.class));
    }

    private ApiEnvConfig sample(Long id, Long apiId, String envCode, String upstreamUrl, int mockEnabled) {
        ApiEnvConfig c = new ApiEnvConfig();
        c.setId(id);
        c.setApiId(apiId);
        c.setEnvCode(envCode);
        c.setUpstreamUrl(upstreamUrl);
        c.setMockEnabled(mockEnabled);
        return c;
    }
}
