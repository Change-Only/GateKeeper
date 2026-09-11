package com.gatekeeper.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PermissionInterceptor 单元测试 — 覆盖有权限/无权限/缺注解/缺 uid 等 11 个场景
 *
 * <p>使用 MockHttpServletRequest/MockHttpServletResponse 模拟 HTTP 调用，
 * 用 @RestController + @GetMapping 注解的 HandlerMethod 让拦截器能扫描到 @RequirePerm。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PermissionInterceptor 拦截逻辑")
class PermissionInterceptorTest {

    @Mock
    private PermissionCacheService permissionCacheService;

    private PermissionInterceptor interceptor;

    /** 用于发现 HandlerMethod */
    @RestController
    static class TestController {
        @GetMapping("/api/high-risk")
        @RequirePerm(value = "app:credential:reset", risk = true)
        public String highRisk() {
            return "ok";
        }

        @GetMapping("/api/normal")
        @RequirePerm(value = "app:list")
        public String normal() {
            return "ok";
        }

        @GetMapping("/api/no-anno")
        public String noAnno() {
            return "ok";
        }
    }

    private TestController controller;
    private HandlerMethod methodHighRisk;
    private HandlerMethod methodNormal;
    private HandlerMethod methodNoAnno;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        interceptor = new PermissionInterceptor(permissionCacheService);
        controller = new TestController();
        methodHighRisk = new HandlerMethod(controller, "highRisk");
        methodNormal = new HandlerMethod(controller, "normal");
        methodNoAnno = new HandlerMethod(controller, "noAnno");
    }

    // ============================================================
    // 场景 1～2：放行 / 拦截（命中 + 缺失）
    // ============================================================

    @Test
    @DisplayName("场景1：有 permCode → 放行")
    void hit_perm_pass() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/high-risk");
        req.setAttribute("X-USER-ID", 1L);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        Set<String> perms = new HashSet<>();
        perms.add("app:credential:reset");
        when(permissionCacheService.getUserPerms(1L)).thenReturn(perms);

        boolean ok = interceptor.preHandle(req, resp, methodHighRisk);
        assertTrue(ok);
    }

    @Test
    @DisplayName("场景2：无 permCode → 拦截，返回 403")
    void miss_perm_reject() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/high-risk");
        req.setAttribute("X-USER-ID", 1L);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        // 用户拥有其它权限但不包含本接口所需
        Set<String> perms = new HashSet<>();
        perms.add("app:list");
        when(permissionCacheService.getUserPerms(1L)).thenReturn(perms);

        boolean ok = interceptor.preHandle(req, resp, methodHighRisk);
        assertFalse(ok);
        String body = resp.getContentAsString();
        assertNotNull(body);
        assertTrue(body.contains("\"code\":403"), "应返回业务码 403");
        assertTrue(body.contains("app:credential:reset"), "响应应包含被拒 permCode");
    }

    // ============================================================
    // 场景 3：handler 不是 HandlerMethod（如静态资源）
    // ============================================================

    @Test
    @DisplayName("场景3：非 HandlerMethod handler → 放行")
    void nonHandlerMethod_pass() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/static/style.css");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        Object fakeHandler = "ResourceHttpRequestHandler";
        boolean ok = interceptor.preHandle(req, resp, fakeHandler);
        assertTrue(ok);
    }

    // ============================================================
    // 场景 4：方法无 @RequirePerm 注解 → 放行（兼容存量）
    // ============================================================

    @Test
    @DisplayName("场景4：方法无 @RequirePerm → 放行")
    void noAnnotation_pass() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/no-anno");
        req.setAttribute("X-USER-ID", 1L);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(req, resp, methodNoAnno);
        assertTrue(ok);
    }

    // ============================================================
    // 场景 5：OPTIONS 预检 → 放行
    // ============================================================

    @Test
    @DisplayName("场景5：OPTIONS CORS 预检 → 放行")
    void optionsPreflight_pass() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("OPTIONS", "/api/high-risk");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(req, resp, methodHighRisk);
        assertTrue(ok);
    }

    // ============================================================
    // 场景 6：uid 缺失（兜底 401）
    // ============================================================

    @Test
    @DisplayName("场景6：X-USER-ID 缺失 → 返回 401")
    void missingUid_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/high-risk");
        // 不设置 X-USER-ID
        MockHttpServletResponse resp = new MockHttpServletResponse();
        boolean ok = interceptor.preHandle(req, resp, methodHighRisk);
        assertFalse(ok);
        String body = resp.getContentAsString();
        assertTrue(body.contains("\"code\":401"));
    }

    // ============================================================
    // 场景 7：uid 是 Integer（JWT 解析常见为 Integer）→ 仍能命中
    // ============================================================

    @Test
    @DisplayName("场景7：uid 是 Integer → 仍能命中")
    void uidInteger_permHit() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/high-risk");
        req.setAttribute("X-USER-ID", 1); // Integer
        MockHttpServletResponse resp = new MockHttpServletResponse();
        Set<String> perms = new HashSet<>();
        perms.add("app:credential:reset");
        when(permissionCacheService.getUserPerms(1L)).thenReturn(perms);
        boolean ok = interceptor.preHandle(req, resp, methodHighRisk);
        assertTrue(ok);
    }

    // ============================================================
    // 场景 8：权限服务抛出 → 不阻断处理
    // ============================================================

    @Test
    @DisplayName("场景8：PermissionCacheService 返回空集合 → 当无权限处理")
    void permServiceEmpty_setDenies() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/high-risk");
        req.setAttribute("X-USER-ID", 1L);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        when(permissionCacheService.getUserPerms(1L)).thenReturn(Collections.emptySet());

        boolean ok = interceptor.preHandle(req, resp, methodHighRisk);
        assertFalse(ok);
        assertTrue(resp.getContentAsString().contains("\"code\":403"));
    }

    // ============================================================
    // 场景 9：命中后把 permCode / risk 写入请求属性
    // ============================================================

    @Test
    @DisplayName("场景9：命中后写 X-PERM-CODE 与 X-PERM-RISK 属性")
    void hit_writesRequestAttrs() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/high-risk");
        req.setAttribute("X-USER-ID", 1L);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        Set<String> perms = new HashSet<>();
        perms.add("app:credential:reset");
        when(permissionCacheService.getUserPerms(1L)).thenReturn(perms);

        boolean ok = interceptor.preHandle(req, resp, methodHighRisk);
        assertTrue(ok);
        assertEquals("app:credential:reset", req.getAttribute("X-PERM-CODE"));
        assertEquals(Boolean.TRUE, req.getAttribute("X-PERM-RISK"));
    }

    // ============================================================
    // 场景 10：无权限响应包含原始 permCode 提示
    // ============================================================

    @Test
    @DisplayName("场景10：拒绝响应 body 包含 permCode")
    void rejectBody_includesPermCode() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/high-risk");
        req.setAttribute("X-USER-ID", 1L);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        when(permissionCacheService.getUserPerms(1L)).thenReturn(Collections.emptySet());

        interceptor.preHandle(req, resp, methodHighRisk);
        String body = resp.getContentAsString();
        assertTrue(body.contains("无权限操作"));
        assertTrue(body.contains("app:credential:reset"));
    }

    // ============================================================
    // 场景 11：HTTP 状态码始终为 200（业务码渲染）
    // ============================================================

    @Test
    @DisplayName("场景11：HTTP 状态 200，业务码 403")
    void httpStatusAlways200OnReject() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/high-risk");
        req.setAttribute("X-USER-ID", 1L);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        when(permissionCacheService.getUserPerms(1L)).thenReturn(Collections.emptySet());

        interceptor.preHandle(req, resp, methodHighRisk);
        assertEquals(200, resp.getStatus(), "HTTP 状态始终 200，业务码区分");
    }
}
