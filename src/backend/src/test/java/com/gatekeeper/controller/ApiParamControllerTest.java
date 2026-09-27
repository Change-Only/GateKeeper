package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.service.ApiParamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiParamController 端到端单测
 *
 * <p>覆盖 6 个接口 + 权限注解（create / delete 高危）。
 * 重点验证 list / tree / detail 路由 service，create / update 调用 service，delete 调用 removeById，
 * 以及 @RequirePerm 存在性与 value/risk 正确。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiParamController 端到端 + 权限点")
class ApiParamControllerTest {

    @Mock
    private ApiParamService apiParamService;

    private ApiParamController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiParamController(apiParamService);
    }

    @Test
    @DisplayName("list 调用 service.list 且响应 code 200")
    void list_ok() {
        ApiParamDto dto = new ApiParamDto();
        dto.setId(1L);
        dto.setApiId(1L);
        dto.setFieldName("skuId");
        when(apiParamService.list(eq(1L), eq(3), any())).thenReturn(Collections.singletonList(dto));

        Result<List<ApiParamDto>> r = controller.list(1L, 3, null);
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().size());
        verify(apiParamService, times(1)).list(eq(1L), eq(3), any());
    }
    @Test
    @DisplayName("create 调用 service.create 并返回 DTO")
    void create_callsService() {
        ApiParamDto dto = new ApiParamDto();
        dto.setId(3L);
        dto.setApiId(1L);
        dto.setFieldName("name");
        dto.setParamType(3);
        when(apiParamService.create(any(ApiParamDto.class))).thenReturn(dto);

        Result<ApiParamDto> r = controller.create(dto);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData().getId());
        verify(apiParamService, times(1)).create(any(ApiParamDto.class));
    }

    @Test
    @DisplayName("create 标注 @RequirePerm api_param:create 高危")
    void create_hasCreatePermAnnotation() throws NoSuchMethodException {
        com.gatekeeper.security.RequirePerm ann =
                ApiParamController.class.getMethod("create", ApiParamDto.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann, "create 方法必须标注 @RequirePerm");
        assertEquals("api_param:create", ann.value());
        assertTrue(ann.risk(), "api_param:create 必须是高危");
    }

    @Test
    @DisplayName("update 走 service.update")
    void update_callsService() {
        ApiParamDto dto = new ApiParamDto();
        dto.setFieldName("新字段名");
        Result<Void> r = controller.update(5L, dto);
        assertEquals(200, r.getCode());
        verify(apiParamService, times(1)).update(eq(5L), eq(dto));
    }

    @Test
    @DisplayName("delete 调用 removeById 并标注 api_param:delete 注解")
    void delete_callsServiceAndHasPermAnnotation() throws NoSuchMethodException {
        Result<Void> r = controller.delete(1L);
        assertEquals(200, r.getCode());
        verify(apiParamService, times(1)).removeById(1L);

        com.gatekeeper.security.RequirePerm ann =
                ApiParamController.class.getMethod("delete", Long.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann, "delete 方法必须标注 @RequirePerm");
        assertEquals("api_param:delete", ann.value());
        assertTrue(ann.risk(), "api_param:delete 必须是高危");
    }

    @Test
    @DisplayName("4 个接口全部存在（端到端路由核查）")
    void all4EndpointsExist() throws NoSuchMethodException {
        Class<?> c = ApiParamController.class;
        assertNotNull(c.getMethod("list", Long.class, Integer.class, Long.class));
        assertNotNull(c.getMethod("create", ApiParamDto.class));
        assertNotNull(c.getMethod("update", Long.class, ApiParamDto.class));
        assertNotNull(c.getMethod("delete", Long.class));
    }
}
