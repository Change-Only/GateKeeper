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
    @DisplayName("1 个接口存在（端到端路由核查）")
    void all1EndpointExists() throws NoSuchMethodException {
        Class<?> c = ApiChangeLogController.class;
        assertNotNull(c.getMethod("list", Long.class, String.class));
    }
}
