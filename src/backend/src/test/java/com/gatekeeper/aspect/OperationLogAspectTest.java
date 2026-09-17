package com.gatekeeper.aspect;

import com.gatekeeper.entity.SysOperationLog;
import com.gatekeeper.service.SysOperationLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OperationLogAspect 单测。
 *
 * <p><b>必须用"带 context-path 的 requestURI"构造用例</b>（{@code /api/xxx}）——
 * 这正是 T18-OSS-1 缺陷的成因：切面拿到的 uri 含 {@code /api}，
 * 若排除判断不先剥 context-path，{@code startsWith("/gateway")} / {@code startsWith("/auth")}
 * 恒为假，网关转发与登录会被误写进管理端操作审计。用无前缀 uri 写用例会掩盖该缺陷。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OperationLogAspect 操作审计")
class OperationLogAspectTest {

    /** 与 application.yml 的 server.servlet.context-path 一致 */
    private static final String CTX = "/api";

    @Mock
    private SysOperationLogService operationLogService;

    private OperationLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new OperationLogAspect(operationLogService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 按真实请求形状绑定上下文：requestURI = contextPath + 业务路径。 */
    private void bindRequest(String contextPath, String businessPath, String method) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContextPath(contextPath);
        req.setRequestURI(contextPath + businessPath);
        req.setMethod(method);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private void bindRequest(String businessPath, String method) {
        bindRequest(CTX, businessPath, method);
    }

    private ProceedingJoinPoint mockPjp() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");
        return pjp;
    }

    /** 执行切面并断言"落 1 条审计"，返回被捕获的审计实体。
     *  先清空历史调用 —— 同一用例内多次调用时 `times(1)` 是**累计**校验，
     *  不清会让第二次调用看到 2 次而误报 TooManyActualInvocations。 */
    private SysOperationLog invokeExpectAudited(ProceedingJoinPoint pjp) throws Throwable {
        clearInvocations(operationLogService);
        assertEquals("ok", aspect.around(pjp), "切面必须原样透传业务返回值");
        ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(operationLogService, times(1)).save(captor.capture());
        return captor.getValue();
    }

    /** 执行切面并断言"不落审计"，且业务仍被放行。 */
    private void invokeExpectSkipped(ProceedingJoinPoint pjp) throws Throwable {
        clearInvocations(operationLogService);
        assertEquals("ok", aspect.around(pjp), "被排除的请求仍必须正常执行业务");
        verify(operationLogService, never()).save(any(SysOperationLog.class));
    }

    // ---------- 核心回归：剥掉 context-path 后排除判断才生效 ----------

    @Test
    @DisplayName("🔴 网关转发 /api/gateway/* 不落管理端审计（context-path 不得导致排除失效）")
    void gatewayForward_notAudited() throws Throwable {
        bindRequest("/gateway/order/create", "POST");
        invokeExpectSkipped(mockPjp());
    }

    @Test
    @DisplayName("🔴 登录 /api/auth/login 不落管理端审计（context-path 不得导致排除失效）")
    void authLogin_notAudited() throws Throwable {
        bindRequest("/auth/login", "POST");
        invokeExpectSkipped(mockPjp());
    }

    @Test
    @DisplayName("登出 /api/auth/logout 同样不落审计")
    void authLogout_notAudited() throws Throwable {
        bindRequest("/auth/logout", "POST");
        invokeExpectSkipped(mockPjp());
    }

    @Test
    @DisplayName("网关转发即使是 PUT/DELETE 也不落审计")
    void gatewayForward_putAndDelete_notAudited() throws Throwable {
        bindRequest("/gateway/order/1", "PUT");
        invokeExpectSkipped(mockPjp());

        bindRequest("/gateway/order/1", "DELETE");
        invokeExpectSkipped(mockPjp());
    }

    // ---------- 排除判断是"首段等值"而非"前缀匹配" ----------

    @Test
    @DisplayName("首段等值匹配：/authorization/* 不得被 /auth 前缀误伤")
    void exclusion_usesSegmentEquality_notPrefix() throws Throwable {
        bindRequest("/authorization/grant", "POST");
        SysOperationLog opLog = invokeExpectAudited(mockPjp());
        assertEquals("AUTHORIZATION", opLog.getOperationModule(),
                "首段 authorization 不在 MODULE_MAP，应回退为大写原值（也证明未被前缀误排除）");
    }

    @Test
    @DisplayName("首段等值匹配：/gateway-report/* 不得被 /gateway 前缀误伤")
    void exclusion_gatewayReport_notConfusedWithGateway() throws Throwable {
        bindRequest("/gateway-report/export", "POST");
        SysOperationLog opLog = invokeExpectAudited(mockPjp());
        assertEquals("GATEWAY-REPORT", opLog.getOperationModule());
    }

    // ---------- 管理端写操作照常审计 ----------

    @Test
    @DisplayName("POST /api/interface/import 落 1 条审计，requestUrl 保留完整含前缀路径")
    void managementWrite_isAuditedWithFullUri() throws Throwable {
        bindRequest("/interface/import", "POST");
        SysOperationLog opLog = invokeExpectAudited(mockPjp());

        assertEquals("INTERFACE", opLog.getOperationModule(), "模块应按剥前缀后的首段推导");
        assertEquals("CREATE", opLog.getOperationType());
        assertEquals("POST", opLog.getRequestMethod());
        // requestUrl 存完整 uri（含 context-path），保持既有数据形状不变
        assertEquals("/api/interface/import", opLog.getRequestUrl());
        assertNotNull(opLog.getCreatedAt());
        assertNotNull(opLog.getOperationDesc());
        org.junit.jupiter.api.Assertions.assertTrue(
                opLog.getOperationDesc().contains("/api/interface/import"),
                "审计描述应含完整请求路径");
    }

    @Test
    @DisplayName("PUT→UPDATE（/api/system/user/1，模块 SYSTEM）；DELETE→DELETE（/api/app/1，模块 APP）")
    void updateAndDelete_typeAndModule() throws Throwable {
        bindRequest("/system/user/1", "PUT");
        SysOperationLog updated = invokeExpectAudited(mockPjp());
        assertEquals("UPDATE", updated.getOperationType());
        assertEquals("SYSTEM", updated.getOperationModule());

        bindRequest("/app/1", "DELETE");
        SysOperationLog deleted = invokeExpectAudited(mockPjp());
        assertEquals("DELETE", deleted.getOperationType());
        assertEquals("APP", deleted.getOperationModule());
    }

    @Test
    @DisplayName("读操作（GET）不落审计")
    void readMethod_notAudited() throws Throwable {
        bindRequest("/interface/list", "GET");
        invokeExpectSkipped(mockPjp());
    }

    // ---------- 模块推导口径（与排除判断共用 businessPath） ----------

    @Test
    @DisplayName("context-path 为空时模块仍取业务路径首段（旧实现会误取 segments[2]）")
    void moduleResolvedEvenWithoutContextPath() throws Throwable {
        bindRequest("", "/app/1", "POST");
        SysOperationLog opLog = invokeExpectAudited(mockPjp());
        assertEquals("APP", opLog.getOperationModule());
        assertEquals("/app/1", opLog.getRequestUrl());
    }

    @Test
    @DisplayName("首段不在 MODULE_MAP 时回退为大写原值，不抛 NPE")
    void moduleFallsBackToUpperCase() throws Throwable {
        bindRequest("/report/export", "POST");
        SysOperationLog opLog = invokeExpectAudited(mockPjp());
        assertEquals("REPORT", opLog.getOperationModule());
    }

    @Test
    @DisplayName("路径仅剩斜杠时不抛异常，模块回退 UNKNOWN")
    void moduleUnknownSegments() throws Throwable {
        bindRequest("", "/", "POST");
        SysOperationLog opLog = invokeExpectAudited(mockPjp());
        assertEquals("UNKNOWN", opLog.getOperationModule());
    }

    // ---------- 操作人与容错 ----------

    @Test
    @DisplayName("操作人取自请求属性 X-USER-ID / X-USERNAME；缺省时为 null 不报错")
    void resolvesOperator() throws Throwable {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContextPath(CTX);
        req.setRequestURI(CTX + "/app");
        req.setMethod("POST");
        req.setAttribute("X-USER-ID", 7L);
        req.setAttribute("X-USERNAME", "alice");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        SysOperationLog opLog = invokeExpectAudited(mockPjp());
        assertEquals(7L, opLog.getOperatorId());
        assertEquals("alice", opLog.getOperatorName());

        // 未登录上下文（属性缺失）时不应抛异常，字段留空
        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        anonymous.setContextPath(CTX);
        anonymous.setRequestURI(CTX + "/app");
        anonymous.setMethod("POST");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(anonymous));

        SysOperationLog anonymousLog = invokeExpectAudited(mockPjp());
        assertNull(anonymousLog.getOperatorId());
        assertNull(anonymousLog.getOperatorName());
    }

    @Test
    @DisplayName("审计写入失败只告警，不影响业务返回值")
    void auditFailureDoesNotBreakBusiness() throws Throwable {
        bindRequest("/app", "POST");
        doThrow(new RuntimeException("db down")).when(operationLogService).save(any(SysOperationLog.class));
        ProceedingJoinPoint pjp = mockPjp();

        assertEquals("ok", aspect.around(pjp), "审计异常不得冒泡到业务");
        verify(operationLogService, times(1)).save(any(SysOperationLog.class));
        verify(pjp, times(1)).proceed();
    }

    @Test
    @DisplayName("无请求上下文（非 Web 线程）直接放行，不落审计")
    void noRequestContext_passThrough() throws Throwable {
        RequestContextHolder.resetRequestAttributes();
        ProceedingJoinPoint pjp = mockPjp();

        assertEquals("ok", aspect.around(pjp));
        verify(operationLogService, never()).save(any(SysOperationLog.class));
    }
}
