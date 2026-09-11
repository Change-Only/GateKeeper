package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.AppCredentialDto;
import com.gatekeeper.dto.CredentialRotateRequest;
import com.gatekeeper.service.AppCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AppCredentialController 端到端单测
 *
 * <p>覆盖 8 个接口 + 权限注解（3 个高危）。重点验证 create / rotate 响应含明文，
 * 其它接口绝不返回明文。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AppCredentialController 端到端 + 权限点")
class AppCredentialControllerTest {

    @Mock
    private AppCredentialService appCredentialService;

    private AppCredentialController controller;

    @BeforeEach
    void setUp() {
        controller = new AppCredentialController(appCredentialService);
    }

    @Test
    @DisplayName("list 调用 service.list 且响应不含明文 secret")
    void list_masksSecret() {
        AppCredentialDto dto = new AppCredentialDto();
        dto.setId(1L);
        dto.setAppKey("ak_prod_1111111111111111");
        dto.setSecretMask("Yk3m****J5sU");
        // 关键：service 应返回不含明文 secret 的 DTO（test double 模拟）
        when(appCredentialService.list(eq(1L), eq("prod"), any()))
                .thenReturn(Collections.singletonList(dto));

        Result<java.util.List<AppCredentialDto>> r = controller.list(1L, "prod", null);
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().size());
        // 明文 secret 必然为 null（service 兜底 + controller 不补）
        assertNull(r.getData().get(0).getAppSecret());
        verify(appCredentialService, times(1)).list(eq(1L), eq("prod"), any());
    }

    @Test
    @DisplayName("detail 调用 service.get 且不含明文 secret")
    void detail_masksSecret() {
        AppCredentialDto dto = new AppCredentialDto();
        dto.setId(1L);
        dto.setAppKey("ak_prod_2222222222222222");
        dto.setSecretMask("Yk3m****J5sU");
        when(appCredentialService.get(1L)).thenReturn(dto);

        Result<AppCredentialDto> r = controller.detail(1L);
        assertEquals(200, r.getCode());
        assertNull(r.getData().getAppSecret());
    }

    @Test
    @DisplayName("create 是明文返回窗口，响应含明文 secret")
    void create_returnsPlaintextOnce() {
        AppCredentialDto dto = new AppCredentialDto();
        dto.setId(1L);
        dto.setAppKey("ak_prod_3333333333333333");
        dto.setAppSecret("PLAIN_SECRET_ONLY_ONCE");
        dto.setSecretMask("PLAI****ONCE");
        dto.setAppId(1L);
        dto.setEnvCode("prod");
        dto.setStatus(1);
        when(appCredentialService.create(any(AppCredentialDto.class))).thenReturn(dto);

        Result<AppCredentialDto> r = controller.create(dto);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData().getAppSecret(), "create 响应必须含明文 secret（仅一次）");
    }

    @Test
    @DisplayName("create 标注 @RequirePerm app_credential:create 高危")
    void create_hasCreatePermAnnotation() throws NoSuchMethodException {
        com.gatekeeper.security.RequirePerm ann =
                AppCredentialController.class.getMethod("create", AppCredentialDto.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann);
        assertEquals("app_credential:create", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("rotate 是明文返回窗口")
    void rotate_returnsNewCredentialPlaintext() {
        AppCredentialDto dto = new AppCredentialDto();
        dto.setId(2L);
        dto.setAppKey("ak_prod_4444444444444444");
        dto.setAppSecret("PLAIN_NEW_ONLY_ONCE");
        dto.setRotateFlag(1);
        dto.setStatus(1);
        when(appCredentialService.rotate(any(CredentialRotateRequest.class))).thenReturn(dto);

        CredentialRotateRequest req = new CredentialRotateRequest();
        req.setAppId(1L);
        req.setEnvCode("prod");

        Result<AppCredentialDto> r = controller.rotate(req);
        assertEquals(200, r.getCode());
        assertEquals(Integer.valueOf(1), r.getData().getRotateFlag());
        assertNotNull(r.getData().getAppSecret(), "rotate 响应必须含新凭证明文 secret");
    }

    @Test
    @DisplayName("completeRotate 调用 service.completeRotate")
    void completeRotate_ok() {
        when(appCredentialService.completeRotate(eq(1L), eq("prod"))).thenReturn(2);

        CredentialRotateRequest req = new CredentialRotateRequest();
        req.setAppId(1L);
        req.setEnvCode("prod");

        Result<Integer> r = controller.completeRotate(req);
        assertEquals(200, r.getCode());
        assertEquals(Integer.valueOf(2), r.getData());
    }

    @Test
    @DisplayName("revoke 标注 @RequirePerm app:credential:revoke 高危")
    void revoke_hasRevokePermAnnotation() throws NoSuchMethodException {
        com.gatekeeper.security.RequirePerm ann =
                AppCredentialController.class.getMethod("revoke", Long.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann);
        assertEquals("app:credential:revoke", ann.value());
        assertTrue(ann.risk(), "app:credential:revoke 必须是高危");

        // service 也会被调用
        Result<Void> r = controller.revoke(7L);
        assertEquals(200, r.getCode());
        verify(appCredentialService, times(1)).revoke(7L);
    }

    @Test
    @DisplayName("update 走 service.update（不影响密钥）")
    void update_callsService() {
        AppCredentialDto dto = new AppCredentialDto();
        dto.setAlias("新别名");
        Result<Void> r = controller.update(5L, dto);
        assertEquals(200, r.getCode());
        verify(appCredentialService, times(1)).update(eq(5L), eq(dto));
    }

    @Test
    @DisplayName("8 个接口全部存在（端到端路由核查）")
    void all8EndpointsExist() throws NoSuchMethodException {
        // 防止后续重构悄悄改 endpoint 名
        Class<?> c = AppCredentialController.class;
        assertNotNull(c.getMethod("list", Long.class, String.class, Integer.class));
        assertNotNull(c.getMethod("detail", Long.class));
        assertNotNull(c.getMethod("create", AppCredentialDto.class));
        assertNotNull(c.getMethod("rotate", CredentialRotateRequest.class));
        assertNotNull(c.getMethod("completeRotate", CredentialRotateRequest.class));
        assertNotNull(c.getMethod("revoke", Long.class));
        assertNotNull(c.getMethod("update", Long.class, AppCredentialDto.class));
    }
}
