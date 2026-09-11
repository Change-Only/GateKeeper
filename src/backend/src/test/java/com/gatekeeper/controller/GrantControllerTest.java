package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.grant.GrantController;
import com.gatekeeper.grant.GrantService;
import com.gatekeeper.security.RequirePerm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
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
 * GrantController 端到端单测 — 镜像 AppCredentialControllerTest 的结构
 *
 * <p>覆盖：各接口调用对应 service 方法；create/approve/reject/revoke 标注正确的
 * {@code @RequirePerm}（value + risk）；并通过反射确认全部端点存在（防止重构改名）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GrantController 端到端 + 权限点")
class GrantControllerTest {

    @Mock
    private GrantService grantService;

    private GrantController controller;

    @BeforeEach
    void setUp() {
        controller = new GrantController(grantService);
    }

    @Test
    @DisplayName("list 调用 grantService.listGrants")
    void list_callsService() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        g.setAppId(1L);
        g.setApiId(2L);
        g.setEnvCode("prod");
        when(grantService.listGrants(eq(1L), eq(2L), eq("prod"), any())).thenReturn(Collections.singletonList(g));

        Result<java.util.List<AppApiGrant>> r = controller.list(1L, 2L, "prod", null);
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().size());
        verify(grantService, times(1)).listGrants(eq(1L), eq(2L), eq("prod"), any());
    }

    @Test
    @DisplayName("detail 调用 grantService.getGrant")
    void detail_callsService() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        when(grantService.getGrant(1L)).thenReturn(g);

        Result<AppApiGrant> r = controller.detail(1L);
        assertEquals(200, r.getCode());
        verify(grantService, times(1)).getGrant(1L);
    }

    @Test
    @DisplayName("create 调用 grantService.createGrant")
    void create_callsService() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        when(grantService.createGrant(any(AppApiGrant.class))).thenReturn(g);

        Result<AppApiGrant> r = controller.create(g);
        assertEquals(200, r.getCode());
        verify(grantService, times(1)).createGrant(any(AppApiGrant.class));
    }

    @Test
    @DisplayName("approve 调用 grantService.approve")
    void approve_callsService() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        when(grantService.approve(eq(1L), any(), any(), any())).thenReturn(g);

        GrantController.GrantAuditRequest req = new GrantController.GrantAuditRequest();
        req.setAuditorId(9L);
        req.setAuditorName("admin");
        req.setAuditRemark("通过");

        Result<AppApiGrant> r = controller.approve(1L, req);
        assertEquals(200, r.getCode());
        verify(grantService, times(1)).approve(eq(1L), any(), any(), any());
    }

    @Test
    @DisplayName("reject 调用 grantService.reject")
    void reject_callsService() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        when(grantService.reject(eq(1L), any(), any(), any())).thenReturn(g);

        GrantController.GrantRejectRequest req = new GrantController.GrantRejectRequest();
        req.setAuditRemark("不合规");

        Result<AppApiGrant> r = controller.reject(1L, req);
        assertEquals(200, r.getCode());
        verify(grantService, times(1)).reject(eq(1L), any(), any(), any());
    }

    @Test
    @DisplayName("revoke 调用 grantService.revoke")
    void revoke_callsService() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        when(grantService.revoke(eq(1L), any(), any(), any())).thenReturn(g);

        GrantController.GrantRevokeRequest req = new GrantController.GrantRevokeRequest();
        req.setRevokeReason("下线");

        Result<AppApiGrant> r = controller.revoke(1L, req);
        assertEquals(200, r.getCode());
        verify(grantService, times(1)).revoke(eq(1L), any(), any(), any());
    }

    @Test
    @DisplayName("renew 调用 grantService.renew")
    void renew_callsService() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        when(grantService.renew(eq(1L), any(LocalDate.class))).thenReturn(g);

        GrantController.GrantRenewRequest req = new GrantController.GrantRenewRequest();
        req.setValidTo(LocalDate.now().plusDays(30));

        Result<AppApiGrant> r = controller.renew(1L, req);
        assertEquals(200, r.getCode());
        verify(grantService, times(1)).renew(eq(1L), any(LocalDate.class));
    }

    @Test
    @DisplayName("pending 调用 grantService.listPending")
    void pending_callsService() {
        when(grantService.listPending(eq("prod"))).thenReturn(Collections.emptyList());

        Result<java.util.List<AppApiGrant>> r = controller.pending("prod");
        assertEquals(200, r.getCode());
        verify(grantService, times(1)).listPending(eq("prod"));
    }

    @Test
    @DisplayName("create 标注 @RequirePerm grant:create 高危")
    void create_hasPermAnnotation() throws NoSuchMethodException {
        RequirePerm ann = GrantController.class.getMethod("create", AppApiGrant.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("grant:create", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("approve 标注 @RequirePerm grant:approve 高危")
    void approve_hasPermAnnotation() throws NoSuchMethodException {
        RequirePerm ann = GrantController.class
                .getMethod("approve", Long.class, GrantController.GrantAuditRequest.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("grant:approve", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("reject 标注 @RequirePerm grant:reject 高危")
    void reject_hasPermAnnotation() throws NoSuchMethodException {
        RequirePerm ann = GrantController.class
                .getMethod("reject", Long.class, GrantController.GrantRejectRequest.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("grant:reject", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("revoke 标注 @RequirePerm grant:revoke 高危")
    void revoke_hasPermAnnotation() throws NoSuchMethodException {
        RequirePerm ann = GrantController.class
                .getMethod("revoke", Long.class, GrantController.GrantRevokeRequest.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("grant:revoke", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("全部接口存在（端到端路由核查）")
    void allEndpointsExist() throws NoSuchMethodException {
        // 防止后续重构悄悄改 endpoint 名
        Class<?> c = GrantController.class;
        assertNotNull(c.getMethod("list", Long.class, Long.class, String.class, Integer.class));
        assertNotNull(c.getMethod("detail", Long.class));
        assertNotNull(c.getMethod("create", AppApiGrant.class));
        assertNotNull(c.getMethod("approve", Long.class, GrantController.GrantAuditRequest.class));
        assertNotNull(c.getMethod("reject", Long.class, GrantController.GrantRejectRequest.class));
        assertNotNull(c.getMethod("revoke", Long.class, GrantController.GrantRevokeRequest.class));
        assertNotNull(c.getMethod("renew", Long.class, GrantController.GrantRenewRequest.class));
        assertNotNull(c.getMethod("pending", String.class));
    }
}
