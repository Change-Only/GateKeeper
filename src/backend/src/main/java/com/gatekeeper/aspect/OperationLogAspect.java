package com.gatekeeper.aspect;

import com.gatekeeper.entity.SysOperationLog;
import com.gatekeeper.service.SysOperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 操作审计切面 — 自动记录管理后台的写操作到 sys_operation_log
 *
 * <p>切点覆盖 {@code com.gatekeeper.controller} 包下全部方法：仅对写操作
 * （POST/PUT/DELETE）且属于管理后台的接口（非网关转发 /auth 登录登出）落审计日志。
 * 操作人取自 JWT 拦截器写入请求属性的 X-USER-ID / X-USERNAME；模块与操作类型
 * 由请求路径首段与 HTTP 方法自动推导，无需在每个接口上重复标注。</p>
 *
 * <p>审计写入失败仅告警、不影响主流程（审计不应阻塞业务）。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final SysOperationLogService operationLogService;

    /** 请求路径首段 → 审计模块枚举 的映射 */
    private static final Map<String, String> MODULE_MAP = new HashMap<>();
    static {
        MODULE_MAP.put("app", "APP");
        MODULE_MAP.put("interface", "INTERFACE");
        MODULE_MAP.put("permission", "PERMISSION");
        MODULE_MAP.put("system", "SYSTEM");
        MODULE_MAP.put("security", "SECURITY");
        MODULE_MAP.put("encryption", "ENCRYPTION");
        MODULE_MAP.put("group", "GROUP");
    }

    /**
     * 环绕通知：拦截 controller 写操作并落审计
     */
    @Around("execution(* com.gatekeeper.controller..*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return pjp.proceed();
        }
        HttpServletRequest request = attrs.getRequest();
        String uri = request.getRequestURI();
        String method = request.getMethod();

        // 仅审计管理后台写操作；排除网关转发（/gateway）与登录登出（/auth）
        if (!isWriteMethod(method) || uri.startsWith("/gateway") || uri.startsWith("/auth")) {
            return pjp.proceed();
        }

        long start = System.currentTimeMillis();
        Object result = pjp.proceed();
        long cost = System.currentTimeMillis() - start;

        try {
            record(request, uri, method, cost);
        } catch (Exception e) {
            // 审计失败不影响业务返回
            log.warn("记录操作审计日志失败: uri={}, reason={}", uri, e.getMessage());
        }
        return result;
    }

    /** 是否为写操作（需要审计） */
    private boolean isWriteMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
    }

    /** 组装并写入审计记录 */
    private void record(HttpServletRequest request, String uri, String method, long cost) {
        String[] segments = uri.split("/");
        String moduleKey = segments.length > 1 ? segments[1] : "UNKNOWN";
        String module = MODULE_MAP.getOrDefault(moduleKey, moduleKey.toUpperCase());

        String type = "POST".equals(method) ? "CREATE" : ("PUT".equals(method) ? "UPDATE" : "DELETE");

        SysOperationLog opLog = new SysOperationLog();
        Object uid = request.getAttribute("X-USER-ID");
        opLog.setOperatorId(uid == null ? null : ((Number) uid).longValue());
        opLog.setOperatorName((String) request.getAttribute("X-USERNAME"));
        opLog.setOperationType(type);
        opLog.setOperationModule(module);
        opLog.setOperationDesc(module + " " + type + " " + uri);
        opLog.setRequestMethod(method);
        opLog.setRequestUrl(uri);
        // 参数仅记录查询串（请求体已被消费，不再重复读取）
        String qs = request.getQueryString();
        opLog.setRequestParams(qs == null ? "" : (qs.length() > 500 ? qs.substring(0, 500) : qs));
        opLog.setClientIp(clientIp(request));
        opLog.setCostTime((int) cost);
        opLog.setCreatedAt(LocalDateTime.now());
        operationLogService.save(opLog);
    }

    /** 获取客户端真实 IP（优先 X-Forwarded-For 首段） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
