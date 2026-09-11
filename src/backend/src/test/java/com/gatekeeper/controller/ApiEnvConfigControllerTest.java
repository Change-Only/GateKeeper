package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiEnvConfigDto;
import com.gatekeeper.service.ApiEnvConfigService;
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
 * ApiEnvConfigController 端到端单测
 *
 * <p>覆盖 6 个接口 + 权限注解（create / delete 高危）。
 * 重点验证 list / detail / toggle-mock 路由 service，create / update 调用 service，
 * @RequirePerm 存在性与 value/risk 正确。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiEnvConfigController 端到端 + 权限点")
class ApiEnvConfigControllerTest {

    @Mock
    private ApiEnvConfigService apiEnvConfigService;

    private ApiEnvConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiEnvConfigController(apiEnvConfigService);
    }

    @Test
    @DisplayName("list 调用 service.list 且响应 code 200")
    void list_ok() {
        ApiEnvConfigDto dto = new ApiEnvConfigDto();
        dto.setId(1L);
        dto.setEnvCode("prod");
        dto.setUpstreamUrl("http://svc");
        when(apiEnvConfigService.list(eq(1L), eq("prod"))).thenReturn(Collections.singletonList(dto));

        Result<List<ApiEnvConfigDto>> r = controller.list(1L, "prod");
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().size());
        verify(apiEnvConfigService, times(1)).list(eq(1L), eq("prod"));
    }

    @Test
    @DisplayName("detail 调用 service.get")
    void detail_ok() {
        ApiEnvConfigDto dto = new ApiEnvConfigDto();
        dto.setId(2L);
        dto.setUpstreamUrl("http://svc2");
        when(apiEnvConfigService.get(2L)).thenReturn(dto);

        Result<ApiEnvConfigDto> r = controller.detail(2L);
        assertEquals(200, r.getCode());
        assertEquals("http://svc2", r.getData().getUpstreamUrl());
    }

    @Test
    @DisplayName("create 调用 service.create 并标注 api_env_config:create 高危")
    void create_callsServiceAndHasPermAnnotation() throws NoSuchMethodException {
        ApiEnvConfigDto dto = new ApiEnvConfigDto();
        dto.setId(3L);
        dto.setApiId(1L);
        dto.setEnvCode("prod");
        dto.setUpstreamUrl("http://svc3");
        when(apiEnvConfigService.create(any(ApiEnvConfigDto.class))).thenReturn(dto);

        Result<ApiEnvConfigDto> r = controller.create(dto);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData().getId());
        verify(apiEnvConfigService, times(1)).create(any(ApiEnvConfigDto.class));

        com.gatekeeper.security.RequirePerm ann =
                ApiEnvConfigController.class.getMethod("create", ApiEnvConfigDto.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann, "create 方法必须标注 @RequirePerm");
        assertEquals("api_env_config:create", ann.value());
        assertTrue(ann.risk(), "api_env_config:create 必须是高危");
    }

    @Test
    @DisplayName("update 走 service.update")
    void update_callsService() {
        ApiEnvConfigDto dto = new ApiEnvConfigDto();
        dto.setUpstreamUrl("http://new");
        Result<Void> r = controller.update(5L, dto);
        assertEquals(200, r.getCode());
        verify(apiEnvConfigService, times(1)).update(eq(5L), eq(dto));
    }

    @Test
    @DisplayName("toggle-mock 走 service.toggleMock")
    void toggleMock_callsService() {
        Result<Void> r = controller.toggleMock(5L);
        assertEquals(200, r.getCode());
        verify(apiEnvConfigService, times(1)).toggleMock(5L);
    }

    @Test
    @DisplayName("delete 调用 removeById 并标注 api_env_config:delete 注解")
    void delete_callsServiceAndHasPermAnnotation() throws NoSuchMethodException {
        Result<Void> r = controller.delete(1L);
        assertEquals(200, r.getCode());
        verify(apiEnvConfigService, times(1)).removeById(1L);

        com.gatekeeper.security.RequirePerm ann =
                ApiEnvConfigController.class.getMethod("delete", Long.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann, "delete 方法必须标注 @RequirePerm");
        assertEquals("api_env_config:delete", ann.value());
        assertTrue(ann.risk(), "api_env_config:delete 必须是高危");
    }

    @Test
    @DisplayName("6 个接口全部存在（端到端路由核查）")
    void all6EndpointsExist() throws NoSuchMethodException {
        Class<?> c = ApiEnvConfigController.class;
        assertNotNull(c.getMethod("list", Long.class, String.class));
        assertNotNull(c.getMethod("detail", Long.class));
        assertNotNull(c.getMethod("create", ApiEnvConfigDto.class));
        assertNotNull(c.getMethod("update", Long.class, ApiEnvConfigDto.class));
        assertNotNull(c.getMethod("toggleMock", Long.class));
        assertNotNull(c.getMethod("delete", Long.class));
    }
}
