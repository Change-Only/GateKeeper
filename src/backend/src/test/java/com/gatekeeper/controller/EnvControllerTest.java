package com.gatekeeper.controller;

import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.dto.EnvDto;
import com.gatekeeper.entity.Env;
import com.gatekeeper.service.EnvService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EnvController 端到端单测
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnvController 端到端")
class EnvControllerTest {

    @Mock
    private EnvService envService;

    private EnvController controller;

    @BeforeEach
    void setUp() {
        controller = new EnvController(envService);
    }

    @Test
    @DisplayName("list 调用 service.pageQuery")
    void list_ok() {
        when(envService.pageQuery(eq(1), eq(10), eq("prod"), any()))
                .thenReturn(PageResult.of(Collections.emptyList(), 0, 1, 10));

        Result<PageResult<EnvDto>> r = controller.list(1, 10, "prod", null);
        assertEquals(200, r.getCode());
    }

    @Test
    @DisplayName("all 走 listEnabled（下拉必用）")
    void all_ok() {
        EnvDto e = new EnvDto();
        e.setId(4L);
        e.setEnvCode("prod");
        e.setHttps(1);
        when(envService.listEnabled()).thenReturn(Collections.singletonList(e));

        Result<java.util.List<EnvDto>> r = controller.all();
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().size());
        assertEquals("prod", r.getData().get(0).getEnvCode());
    }

    @Test
    @DisplayName("detail 不存在 404")
    void detail_notFound() {
        when(envService.getEnv(99L)).thenReturn(null);
        Result<Env> r = controller.detail(99L);
        assertEquals(404, r.getCode());
    }

    @Test
    @DisplayName("create 走 service.createEnv")
    void create_ok() {
        EnvDto dto = new EnvDto();
        dto.setEnvCode("pre");
        dto.setEnvName("预发");
        dto.setGatewayUrl("https://api-pre.example.com");

        Env e = new Env();
        e.setId(3L);
        when(envService.createEnv(any(EnvDto.class))).thenReturn(e);

        Result<Env> r = controller.create(dto);
        assertEquals(200, r.getCode());
        verify(envService, times(1)).createEnv(any(EnvDto.class));
    }

    @Test
    @DisplayName("update 走 service.updateEnv（envCode 不可改）")
    void update_ok() {
        EnvDto dto = new EnvDto();
        dto.setId(3L);
        dto.setEnvCode("pre");
        Result<Void> r = controller.update(dto);
        assertEquals(200, r.getCode());
        verify(envService, times(1)).updateEnv(eq(3L), eq(dto));
    }

    @Test
    @DisplayName("delete 标注 @RequirePerm env:delete 高危")
    void delete_hasPermAnnotation() throws NoSuchMethodException {
        Result<Void> r = controller.delete(4L);
        assertEquals(200, r.getCode());
        verify(envService, times(1)).deleteEnv(4L);

        com.gatekeeper.security.RequirePerm ann =
                EnvController.class.getMethod("delete", Long.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann);
        assertEquals("env:delete", ann.value());
        assertTrue(ann.risk());
    }
}
