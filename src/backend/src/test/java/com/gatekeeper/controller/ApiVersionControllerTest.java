package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.service.ApiVersionService;
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
 * ApiVersionController 端到端单测
 *
 * <p>覆盖 7 个接口 + 权限注解（create 高危）。
 * 重点验证 current / set-current / deprecate / offline 调用 service，@RequirePerm 存在性与 value/risk 正确。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiVersionController 端到端 + 权限点")
class ApiVersionControllerTest {

    @Mock
    private ApiVersionService apiVersionService;

    private ApiVersionController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiVersionController(apiVersionService);
    }

    @Test
    @DisplayName("list 调用 service.list")
    void list_ok() {
        ApiVersionDto dto = new ApiVersionDto();
        dto.setId(1L);
        dto.setVersion("v1");
        when(apiVersionService.list(eq(1L))).thenReturn(Collections.singletonList(dto));

        Result<List<ApiVersionDto>> r = controller.list(1L);
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().size());
        verify(apiVersionService, times(1)).list(eq(1L));
    }

    @Test
    @DisplayName("current 调用 service.current")
    void current_ok() {
        ApiVersionDto dto = new ApiVersionDto();
        dto.setId(1L);
        dto.setVersion("v1");
        dto.setIsCurrent(1);
        when(apiVersionService.current(1L)).thenReturn(dto);

        Result<ApiVersionDto> r = controller.current(1L);
        assertEquals(200, r.getCode());
        assertEquals(Integer.valueOf(1), r.getData().getIsCurrent());
    }

    @Test
    @DisplayName("detail 调用 service.get")
    void detail_ok() {
        ApiVersionDto dto = new ApiVersionDto();
        dto.setId(2L);
        dto.setVersion("v2");
        when(apiVersionService.get(2L)).thenReturn(dto);

        Result<ApiVersionDto> r = controller.detail(2L);
        assertEquals(200, r.getCode());
        assertEquals("v2", r.getData().getVersion());
    }

    @Test
    @DisplayName("create 调用 service.create 并标注 api_version:create 高危")
    void create_callsServiceAndHasPermAnnotation() throws NoSuchMethodException {
        ApiVersionDto dto = new ApiVersionDto();
        dto.setId(3L);
        dto.setApiId(1L);
        dto.setVersion("v3");
        when(apiVersionService.create(any(ApiVersionDto.class))).thenReturn(dto);

        Result<ApiVersionDto> r = controller.create(dto);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData().getId());
        verify(apiVersionService, times(1)).create(any(ApiVersionDto.class));

        com.gatekeeper.security.RequirePerm ann =
                ApiVersionController.class.getMethod("create", ApiVersionDto.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann, "create 方法必须标注 @RequirePerm");
        assertEquals("api_version:create", ann.value());
        assertTrue(ann.risk(), "api_version:create 必须是高危");
    }

    @Test
    @DisplayName("set-current 走 service.setCurrent")
    void setCurrent_callsService() {
        Result<Void> r = controller.setCurrent(5L);
        assertEquals(200, r.getCode());
        verify(apiVersionService, times(1)).setCurrent(5L);
    }

    @Test
    @DisplayName("deprecate 走 service.deprecate")
    void deprecate_callsService() {
        Result<Void> r = controller.deprecate(5L);
        assertEquals(200, r.getCode());
        verify(apiVersionService, times(1)).deprecate(5L);
    }

    @Test
    @DisplayName("offline 走 service.offline")
    void offline_callsService() {
        Result<Void> r = controller.offline(5L);
        assertEquals(200, r.getCode());
        verify(apiVersionService, times(1)).offline(5L);
    }

    @Test
    @DisplayName("7 个接口全部存在（端到端路由核查）")
    void all7EndpointsExist() throws NoSuchMethodException {
        Class<?> c = ApiVersionController.class;
        assertNotNull(c.getMethod("list", Long.class));
        assertNotNull(c.getMethod("current", Long.class));
        assertNotNull(c.getMethod("detail", Long.class));
        assertNotNull(c.getMethod("create", ApiVersionDto.class));
        assertNotNull(c.getMethod("setCurrent", Long.class));
        assertNotNull(c.getMethod("deprecate", Long.class));
        assertNotNull(c.getMethod("offline", Long.class));
    }
}
