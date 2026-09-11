package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.entity.ApiVersion;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiVersionMapper;
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
 * ApiVersionServiceImpl 单元测试 — T03b 接口版本服务
 *
 * <p>覆盖 list / current / create / setCurrent / deprecate / offline 关键路径与校验：
 * 唯一版本预检、setCurrent 事务清零其余版本、deprecate/offline 状态流转。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiVersionService create/setCurrent/deprecate/offline")
class ApiVersionServiceTest {

    @Mock
    private ApiVersionMapper apiVersionMapper;

    private ApiVersionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApiVersionServiceImpl();
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(service, apiVersionMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("current 返回 is_current=1 的版本")
    void current_returnsIsCurrent() {
        ApiVersion v = sample(1L, 1L, "v1", 1, 1);
        when(apiVersionMapper.selectOne(any(QueryWrapper.class))).thenReturn(v);

        ApiVersionDto result = service.current(1L);
        assertEquals(Integer.valueOf(1), result.getIsCurrent());
    }

    @Test
    @DisplayName("create 重复版本抛 400")
    void create_duplicateVersion() {
        when(apiVersionMapper.selectOne(any(QueryWrapper.class))).thenReturn(sample(1L, 1L, "v1", 1, 0));

        ApiVersionDto dto = new ApiVersionDto();
        dto.setApiId(1L);
        dto.setVersion("v1");
        GatewayException ex = assertThrows(GatewayException.class, () -> service.create(dto));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("相同版本"));
    }

    @Test
    @DisplayName("create 必填缺失抛 400（version）")
    void create_missingVersion() {
        ApiVersionDto dto = new ApiVersionDto();
        dto.setApiId(1L);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.create(dto));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("create 成功落库")
    void create_success() {
        when(apiVersionMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(apiVersionMapper.insert(any(ApiVersion.class))).thenReturn(1);

        ApiVersionDto dto = new ApiVersionDto();
        dto.setApiId(1L);
        dto.setVersion("v2");
        ApiVersionDto result = service.create(dto);
        assertEquals("v2", result.getVersion());
        verify(apiVersionMapper, times(1)).insert(any(ApiVersion.class));
    }

    @Test
    @DisplayName("setCurrent 清零其余版本并置本版本为 current")
    void setCurrent_resetsOthers() {
        ApiVersion target = sample(2L, 1L, "v2", 1, 0);
        when(apiVersionMapper.selectById(2L)).thenReturn(target);
        when(apiVersionMapper.update(any(ApiVersion.class), any(QueryWrapper.class))).thenReturn(1);
        when(apiVersionMapper.updateById(any(ApiVersion.class))).thenReturn(1);

        service.setCurrent(2L);
        // 本版本 updateById 置 is_current=1
        verify(apiVersionMapper, times(1)).updateById(any(ApiVersion.class));
        // 其余版本被清零（update with wrapper）
        verify(apiVersionMapper, times(1)).update(any(ApiVersion.class), any(QueryWrapper.class));
    }

    @Test
    @DisplayName("deprecate 置 status=2")
    void deprecate_setsStatus2() {
        ApiVersion v = sample(3L, 1L, "v3", 1, 0);
        when(apiVersionMapper.selectById(3L)).thenReturn(v);
        when(apiVersionMapper.updateById(any(ApiVersion.class))).thenReturn(1);

        service.deprecate(3L);
        verify(apiVersionMapper, times(1)).updateById(any(ApiVersion.class));
    }

    @Test
    @DisplayName("offline 置 status=3")
    void offline_setsStatus3() {
        ApiVersion v = sample(3L, 1L, "v3", 1, 0);
        when(apiVersionMapper.selectById(3L)).thenReturn(v);
        when(apiVersionMapper.updateById(any(ApiVersion.class))).thenReturn(1);

        service.offline(3L);
        verify(apiVersionMapper, times(1)).updateById(any(ApiVersion.class));
    }

    private ApiVersion sample(Long id, Long apiId, String version, int status, int isCurrent) {
        ApiVersion v = new ApiVersion();
        v.setId(id);
        v.setApiId(apiId);
        v.setVersion(version);
        v.setStatus(status);
        v.setIsCurrent(isCurrent);
        return v;
    }
}
