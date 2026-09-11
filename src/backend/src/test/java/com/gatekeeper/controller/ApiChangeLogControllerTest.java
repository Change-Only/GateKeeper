package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiChangeLogDto;
import com.gatekeeper.service.ApiChangeLogService;
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
 * ApiChangeLogController 端到端单测
 *
 * <p>覆盖 3 个接口（追加型，无 update/delete）+ 权限注解（append 高危）。
 * 重点验证 list / detail 路由 service，append 调用 service.append，
 * @RequirePerm 存在性与 value/risk 正确。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiChangeLogController 端到端 + 权限点")
class ApiChangeLogControllerTest {

    @Mock
    private ApiChangeLogService apiChangeLogService;

    private ApiChangeLogController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiChangeLogController(apiChangeLogService);
    }

    @Test
    @DisplayName("list 调用 service.list 且响应 code 200")
    void list_ok() {
        ApiChangeLogDto dto = new ApiChangeLogDto();
        dto.setId(1L);
        dto.setApiId(1L);
        dto.setChangeType("UPDATE");
        when(apiChangeLogService.list(eq(1L), eq("UPDATE"))).thenReturn(Collections.singletonList(dto));

        Result<List<ApiChangeLogDto>> r = controller.list(1L, "UPDATE");
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().size());
        verify(apiChangeLogService, times(1)).list(eq(1L), eq("UPDATE"));
    }

    @Test
    @DisplayName("detail 调用 service.get")
    void detail_ok() {
        ApiChangeLogDto dto = new ApiChangeLogDto();
        dto.setId(2L);
        dto.setChangeType("CREATE");
        when(apiChangeLogService.get(2L)).thenReturn(dto);

        Result<ApiChangeLogDto> r = controller.detail(2L);
        assertEquals(200, r.getCode());
        assertEquals("CREATE", r.getData().getChangeType());
    }

    @Test
    @DisplayName("append 调用 service.append 并标注 api_change_log:append 高危")
    void append_callsServiceAndHasPermAnnotation() throws NoSuchMethodException {
        ApiChangeLogDto dto = new ApiChangeLogDto();
        dto.setId(3L);
        dto.setApiId(1L);
        dto.setChangeType("UPDATE");
        when(apiChangeLogService.append(any(ApiChangeLogDto.class))).thenReturn(dto);

        Result<ApiChangeLogDto> r = controller.append(dto);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData().getId());
        verify(apiChangeLogService, times(1)).append(any(ApiChangeLogDto.class));

        com.gatekeeper.security.RequirePerm ann =
                ApiChangeLogController.class.getMethod("append", ApiChangeLogDto.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann, "append 方法必须标注 @RequirePerm");
        assertEquals("api_change_log:append", ann.value());
        assertTrue(ann.risk(), "api_change_log:append 必须是高危");
    }

    @Test
    @DisplayName("3 个接口全部存在（端到端路由核查）")
    void all3EndpointsExist() throws NoSuchMethodException {
        Class<?> c = ApiChangeLogController.class;
        assertNotNull(c.getMethod("list", Long.class, String.class));
        assertNotNull(c.getMethod("detail", Long.class));
        assertNotNull(c.getMethod("append", ApiChangeLogDto.class));
    }
}
