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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 操作审计切面 — 自动记录管理后台的写操作到 sys_operation_log
 *
 * <p>切点覆盖 {@code com.gatekeeper.controller} 包下全部方法：仅对写操作
 * （POST/PUT/DELETE）且属于管理后台的接口（非网关转发 /auth 登录登出）落审计日志。
 * 操作人取自 JWT 拦截器写入请求属性的 X-USER-ID / X-USERNAME；模块与操作类型
 * 由请求路径首段与 HTTP 方法自动推导，无需在每个接口上重复标注。</p>
 *
 * <p><b>路径口径（T18-OSS-1 修正）</b>：{@code request.getRequestURI()} 含 context-path
 * （本项目为 {@code /api}），因此所有"按路径首段判断"的逻辑都必须先剥掉 context-path。
 * 排除判断与 {@link #record} 的模块推导统一走 {@link #businessPath}，
 * 避免二者口径分叉（历史上排除判断漏剥，导致 {@code /api/gateway/*} 与
 * {@code /api/auth/login} 被写进管理端审计）。</p>
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
     * 不落审计的路径首段（业务路径，已剥 context-path）。
     *
     * <p>{@code gateway}：网关转发流量由 {@code api_call_log} 单独覆盖，不属于"管理端操作"；
     * {@code auth}：登录/登出是会话建立动作，非管理操作。</p>
     *
     * <p>用<b>等值</b>而非 {@code startsWith} 前缀匹配 —— 前缀匹配会把将来可能的
     * {@code /authorization}、{@code /gateway-report} 一并静默排除，属于隐性误伤。</p>
     */
    private static final Set<String> EXCLUDED_SEGMENTS =
            new HashSet<>(Arrays.asList("gateway", "auth"));

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
        // 🔴 getRequestURI() 含 context-path（/api）⇒ 必须先剥掉再判首段。
        //    历史缺陷：直接 uri.startsWith("/gateway")/("/auth") 恒为假，
        //    导致网关转发（/api/gateway/*）与登录（/api/auth/login）被写进管理端操作审计。
        String path = businessPath(request);

        // 仅审计管理后台写操作；排除网关转发（/gateway）与登录登出（/auth）
        if (!isWriteMethod(method) || EXCLUDED_SEGMENTS.contains(firstSegment(path))) {
            return pjp.proceed();
        }

        long start = System.currentTimeMillis();
        Object result = pjp.proceed();
        long cost = System.currentTimeMillis() - start;

        try {
            record(request, uri, path, method, cost);
        } catch (Exception e) {
            // 审计失败不影响业务返回
            log.warn("记录操作审计日志失败: uri={}, reason={}", uri, e.getMessage());
        }
        return result;
    }

    /**
     * 剥离 context-path 后的业务路径（始终以 {@code /} 开头）。
     *
     * <p>例：context-path={@code /api}、requestURI={@code /api/gateway/order} ⇒ {@code /gateway/order}。
     * 用 {@code getContextPath()} 的长度而非硬编码 {@code "/api"}，部署换 context-path 也不会失效。</p>
     */
    private String businessPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri.startsWith("/") ? uri : "/" + uri;
    }

    /** 是否为写操作（需要审计） */
    private boolean isWriteMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
    }

    /** 组装并写入审计记录（{@code path} 为已剥 context-path 的业务路径，仅用于模块推导） */
    private void record(HttpServletRequest request, String uri, String path, String method, long cost) {
        // 模块名取业务路径首段——T10-E 修正：原取含 context-path 的 segments[1] 恒为 "api"，
        // MODULE_MAP 永不命中，operation_module 恒 "API"。
        // T18-OSS-1：改为与排除判断共用 businessPath()，两端口径不再分叉。
        String moduleKey = firstSegment(path);
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
        // requestUrl 仍存完整 uri（含 context-path），保持既有数据形状不变
        opLog.setRequestUrl(uri);
        // 参数仅记录查询串（请求体已被消费，不再重复读取）
        String qs = request.getQueryString();
        opLog.setRequestParams(qs == null ? "" : (qs.length() > 500 ? qs.substring(0, 500) : qs));
        opLog.setClientIp(clientIp(request));
        opLog.setCostTime((int) cost);
        opLog.setCreatedAt(LocalDateTime.now());
        operationLogService.save(opLog);
    }

    /** 取 {@code /a/b/c} 的首段 {@code a}；无段时返回 {@code UNKNOWN}。 */
    private String firstSegment(String path) {
        if (path == null) {
            return "UNKNOWN";
        }
        int start = path.startsWith("/") ? 1 : 0;
        int end = path.indexOf('/', start);
        String seg = end < 0 ? path.substring(start) : path.substring(start, end);
        return seg.isEmpty() ? "UNKNOWN" : seg;
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
