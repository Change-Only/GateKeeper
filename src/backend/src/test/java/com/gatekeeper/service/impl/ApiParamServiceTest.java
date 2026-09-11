package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiParamMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiParamServiceImpl 单元测试 — T03b 接口参数服务
 *
 * <p>覆盖 list / get / tree / create / update 关键路径与校验：必填校验、树形构建、仅可编辑字段更新。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiParamService list/tree/create/update")
class ApiParamServiceTest {

    @Mock
    private ApiParamMapper apiParamMapper;

    private ApiParamServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApiParamServiceImpl();
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(service, apiParamMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("list 按 apiId/paramType 过滤")
    void list_filters() {
        ApiParam p = sample(1L, 1L, 0L, "skuId", 3);
        when(apiParamMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(p));

        List<ApiParamDto> result = service.list(1L, 3, null);
        assertEquals(1, result.size());
        assertEquals("skuId", result.get(0).getFieldName());
    }

    @Test
    @DisplayName("get 不存在时 404")
    void get_notFound() {
        when(apiParamMapper.selectById(99L)).thenReturn(null);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.get(99L));
        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("tree 构建嵌套结构：root(parentId=0) 含 children")
    void tree_buildsHierarchy() {
        ApiParam root = sample(1L, 1L, 0L, "items", 3);
        ApiParam child = sample(2L, 1L, 1L, "skuId", 3);
        ApiParam root2 = sample(3L, 1L, 0L, "page", 3);
        when(apiParamMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(java.util.Arrays.asList(root, child, root2));

        List<ApiParamDto> roots = service.tree(1L);
        assertEquals(2, roots.size(), "应有两个根节点");
        ApiParamDto items = roots.stream().filter(r -> "items".equals(r.getFieldName())).findFirst().orElse(null);
        assertNotNull(items);
        assertNotNull(items.getChildren());
        assertEquals(1, items.getChildren().size());
        assertEquals("skuId", items.getChildren().get(0).getFieldName());
    }

    @Test
    @DisplayName("create 必填缺失抛 400（fieldName）")
    void create_missingFieldName() {
        ApiParamDto dto = new ApiParamDto();
        dto.setApiId(1L);
        dto.setParamType(3);
        // fieldName 为空
        GatewayException ex = assertThrows(GatewayException.class, () -> service.create(dto));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("create 必填缺失抛 400（paramType）")
    void create_missingParamType() {
        ApiParamDto dto = new ApiParamDto();
        dto.setApiId(1L);
        dto.setFieldName("name");
        GatewayException ex = assertThrows(GatewayException.class, () -> service.create(dto));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("create 成功落库并返回 DTO")
    void create_success() {
        ApiParamDto dto = new ApiParamDto();
        dto.setApiId(1L);
        dto.setFieldName("name");
        dto.setParamType(3);
        when(apiParamMapper.insert(any(ApiParam.class))).thenReturn(1);

        ApiParamDto result = service.create(dto);
        assertNotNull(result);
        assertEquals("name", result.getFieldName());
        verify(apiParamMapper, times(1)).insert(any(ApiParam.class));
    }

    @Test
    @DisplayName("update 仅修改可编辑字段，结构键不变")
    void update_onlyEditableFields() {
        ApiParam existing = sample(5L, 1L, 0L, "oldName", 3);
        when(apiParamMapper.selectById(5L)).thenReturn(existing);

        ApiParamDto dto = new ApiParamDto();
        dto.setFieldName("newName");
        dto.setRequired(1);

        service.update(5L, dto);
        verify(apiParamMapper, times(1)).updateById(any(ApiParam.class));
    }

    private ApiParam sample(Long id, Long apiId, Long parentId, String fieldName, Integer paramType) {
        ApiParam p = new ApiParam();
        p.setId(id);
        p.setApiId(apiId);
        p.setParentId(parentId);
        p.setFieldName(fieldName);
        p.setParamType(paramType);
        p.setRequired(0);
        p.setSortOrder(0);
        return p;
    }
}
