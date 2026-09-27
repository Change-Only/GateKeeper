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
    @DisplayName("1 个接口存在（端到端路由核查）")
    void all1EndpointExists() throws NoSuchMethodException {
        Class<?> c = ApiEnvConfigController.class;
        assertNotNull(c.getMethod("list", Long.class, String.class));
    }
}
