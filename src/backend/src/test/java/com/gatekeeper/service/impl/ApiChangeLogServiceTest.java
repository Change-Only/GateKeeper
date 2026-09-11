package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.ApiChangeLogDto;
import com.gatekeeper.entity.ApiChangeLog;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiChangeLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiChangeLogServiceImpl 单元测试 — T03b 接口变更历史服务（追加型）
 *
 * <p>覆盖 list / get / append 关键路径与校验：必填校验、createTime 由服务端填充。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiChangeLogService list/append")
class ApiChangeLogServiceTest {

    @Mock
    private ApiChangeLogMapper apiChangeLogMapper;

    private ApiChangeLogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApiChangeLogServiceImpl();
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(service, apiChangeLogMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("list 按 apiId/changeType 过滤并倒序")
    void list_filters() {
        ApiChangeLog log = sample(1L, 1L, "UPDATE");
        when(apiChangeLogMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(log));

        List<ApiChangeLogDto> result = service.list(1L, "UPDATE");
        assertEquals(1, result.size());
        assertEquals("UPDATE", result.get(0).getChangeType());
    }

    @Test
    @DisplayName("get 不存在时 404")
    void get_notFound() {
        when(apiChangeLogMapper.selectById(99L)).thenReturn(null);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.get(99L));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("append 必填缺失抛 400（changeType）")
    void append_missingChangeType() {
        ApiChangeLogDto dto = new ApiChangeLogDto();
        dto.setApiId(1L);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.append(dto));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("append 服务端填充 createTime 并入库存证")
    void append_setsCreateTimeAndInserts() {
        when(apiChangeLogMapper.insert(any(ApiChangeLog.class))).thenReturn(1);

        ApiChangeLogDto dto = new ApiChangeLogDto();
        dto.setApiId(1L);
        dto.setChangeType("UPDATE");
        ApiChangeLogDto result = service.append(dto);
        assertNotNull(result);
        assertNotNull(result.getCreateTime(), "createTime 应由服务端填充");
        verify(apiChangeLogMapper, times(1)).insert(any(ApiChangeLog.class));
    }

    private ApiChangeLog sample(Long id, Long apiId, String changeType) {
        ApiChangeLog log = new ApiChangeLog();
        log.setId(id);
        log.setApiId(apiId);
        log.setChangeType(changeType);
        log.setCreateTime(LocalDateTime.now());
        return log;
    }
}
