package com.gatekeeper.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 权限点拦截器 — 在 JwtAuthInterceptor 之后执行，按 @RequirePerm 进行权限点校验
 *
 * <p>执行时机：Spring MVC 拦截器按注册顺序执行，本拦截器在 WebConfig 中注册在
 * JwtAuthInterceptor 之后，确保 X-USER-ID 已由 JwtAuthInterceptor 写入请求属性。</p>
 *
 * <p>三类行为：
 * <ul>
 *   <li>handler 是 HandlerMethod 且标注了 @RequirePerm → 校验 permCode 集合</li>
 *   <li>handler 是 HandlerMethod 无注解 → <b>写方法默认拒绝、只读方法默认放行</b>（见下）</li>
 *   <li>非 HandlerMethod（如静态资源 handler）→ 放行</li>
 * </ul>
 * </p>
 *
 * <h3>P0-1 修复（2026-09-19 安全审计 T-SEC-1）</h3>
 * <p>修复前：无 @RequirePerm 的 handler 一律放行，导致 10 个写端点（延期授权、启停封禁/告警规则、
 * 改写加密配置、标记告警已读）可被<b>任意登录账号</b>调用。修复后采用「按 HTTP 动词分治」的默认策略：</p>
 * <ul>
 *   <li><b>写方法（POST/PUT/DELETE/PATCH）默认拒绝</b>：无注解即 403，仅放行 {@link #PUBLIC_WRITE_ENDPOINTS}
 *       中显式登记的公开写端点（当前只有登录接口）。这一步一次性消除上述 10 个写端点的暴露面，
 *       且<b>不影响任何只读端点</b>。</li>
 *   <li><b>只读方法（GET/HEAD/TRACE/OPTIONS）维持既有默认放行</b>：修复前全仓 <b>89</b> 个无注解只读端点
 *       （口径：212 个方法级端点 − 112 个受保护 − 11 个无注解写端点 = 89）被前端大量调用，
 *       把它们全部列入白名单与「默认放行」在安全上完全等价（零收益），漏列任意一个却会造成 403 回归。
 *       ⇒ 读侧仍是「默认允许」，需后续单独做白名单收敛，本轮<b>不假装已修</b>。</li>
 *   <li>10 个写端点已逐个补 {@code @RequirePerm}（复用既有播种权限码，零新增）。</li>
 * </ul>
 *
 * <p><b>P2-9 修复</b>：{@code @RequirePerm} 未写 {@code value} 时由 fail-open 放行改为 fail-closed 拒绝。</p>
 *
 * <p>权限点来源：{@link PermissionCacheService#getUserPerms(Long)} 读 Redis gk:perm:{uid}。</p>
 *
 * <p>关于 JwtUtil：本拦截器不直接解析 Token，因为 JwtAuthInterceptor 已经把
 * uid 写入请求属性 X-USER-ID（Long 类型，源自 JWT claim "uid"）；
 * JwtUtil 仅作为 JwtAuthInterceptor 内部依赖保留。</p>
 *
 * @author GateKeeper
 * @since T02 (APIM V2)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    /**
     * 写方法集合 —— 无 @RequirePerm 时默认拒绝（P0-1）。
     *
     * <p>只读方法（GET/HEAD/TRACE）与 CORS 预检（OPTIONS）不在其中，维持默认放行。</p>
     */
    private static final Set<String> WRITE_METHODS =
            new HashSet<>(Arrays.asList("POST", "PUT", "DELETE", "PATCH"));

    /**
     * 「公开写端点」白名单（P0-1）—— 无 @RequirePerm 也允许调用的写端点，必须<b>极小</b>。
     *
     * <p>键格式为 {@code "METHOD 业务路径"}，其中「业务路径」= 去掉 context-path 的 requestURI
     * （与 {@code config/WebConfig}、{@code aspect/OperationLogAspect} 同一口径，不硬编码 {@code /api}）。
     * 采用<b>精确等值</b>匹配而非前缀匹配，避免 {@code /auth/loginXxx} 之类的绕过（源自 §19.6 教训）。</p>
     *
     * <p>{@code POST /auth/login} 在 {@link com.gatekeeper.config.WebConfig} 中已被
     * {@code excludePathPatterns} 放行、不会进入本拦截器；此处登记属<b>防御性兜底</b>：
     * 防止未来有人误删 WebConfig 的放行规则而把登录接口一并锁死（登录是获取令牌的唯一入口）。</p>
     */
    private static final Set<String> PUBLIC_WRITE_ENDPOINTS =
            new HashSet<>(Arrays.asList("POST /auth/login"));

    private final PermissionCacheService permissionCacheService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        // 放行预检请求（CORS OPTIONS）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 仅对带 @RequirePerm 的 HandlerMethod 做拦截
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod handlerMethod = (HandlerMethod) handler;

        // 方法级优先；方法无注解时尝试类级注解
        RequirePerm methodAnnotation = handlerMethod.getMethodAnnotation(RequirePerm.class);
        RequirePerm classAnnotation = handlerMethod.getBeanType().getAnnotation(RequirePerm.class);
        RequirePerm requirePerm = methodAnnotation != null ? methodAnnotation : classAnnotation;

        if (requirePerm == null) {
            // ===================== P0-1：默认策略按 HTTP 动词分治 =====================
            String httpMethod = request.getMethod() == null ? "" : request.getMethod().toUpperCase();
            if (!WRITE_METHODS.contains(httpMethod)) {
                // 只读方法（GET/HEAD/TRACE 等）→ 维持既有默认放行。
                // ⚠️ 读侧仍是「默认允许」，需后续单独收敛为白名单；本轮不假装已修。
                return true;
            }
            // 写方法：默认拒绝，仅放行显式登记的公开写端点
            if (isPublicWriteEndpoint(request)) {
                return true;
            }
            log.warn("Blocked un-annotated write endpoint: {} {} (未登记 @RequirePerm)",
                    request.getMethod(), request.getRequestURI());
            reject(response, 403, "该接口未登记访问策略，已拒绝");
            return false;
        }

        // 解析当前登录 uid（X-USER-ID 由 JwtAuthInterceptor 写入）
        Object uidAttr = request.getAttribute("X-USER-ID");
        if (uidAttr == null) {
            // JwtAuthInterceptor 已经拦截过了不该走到这里，但做兜底返回 401
            reject(response, 401, "未登录或登录已过期");
            return false;
        }
        Long uid = toLong(uidAttr);

        // 加载权限点
        Set<String> userPerms = permissionCacheService.getUserPerms(uid);

        // 校验：MVP 阶段支持单个 permCode（注解不可重复）
        String requiredCode = requirePerm.value();
        if (requiredCode == null || requiredCode.isEmpty()) {
            // ===== P2-9：空 value 由 fail-open（放行）改为 fail-closed（拒绝）=====
            log.error("Blocked: @RequirePerm.value() is empty for handler={}",
                    handlerMethod.getMethod().getName());
            reject(response, 403, "权限点配置错误：该接口未正确登记权限点，已拒绝");
            return false;
        }

        // 命中即放行（MVP 阶段单 permCode）
        boolean hit = userPerms.contains(requiredCode);
        if (!hit) {
            log.warn("Perm denied: uid={}, required={}, hasCount={}, hasSample={}",
                    uid, requiredCode, userPerms.size(),
                    userPerms.isEmpty() ? "[]" : userPerms.iterator().next());
            reject(response, 403, "无权限操作：" + requiredCode);
            return false;
        }

        // 写入 perm 属性，供 OperationLogAspect 写审计使用
        request.setAttribute("X-PERM-CODE", requiredCode);
        request.setAttribute("X-PERM-RISK", requirePerm.risk());
        return true;
    }

    /**
     * 判定当前请求是否命中「公开写端点」白名单（精确等值）。
     *
     * @param request 当前请求
     * @return true=属于公开写端点，应放行
     */
    private boolean isPublicWriteEndpoint(HttpServletRequest request) {
        String key = (request.getMethod() == null ? "" : request.getMethod().toUpperCase())
                + " " + businessPath(request);
        return PUBLIC_WRITE_ENDPOINTS.contains(key);
    }

    /**
     * 业务路径 = requestURI 去掉 context-path。
     *
     * <p>统一口径（源自 CONTRACTS §19.6）：<b>不硬编码 {@code /api}</b>，避免 context-path 变更后
     * 白名单静默失配。</p>
     *
     * @param request 当前请求
     * @return 去掉 context-path 后的路径，例如 {@code /auth/login}
     */
    private String businessPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (uri == null) {
            return "";
        }
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            return uri.substring(ctx.length());
        }
        return uri;
    }

    /**
     * 解析 uid（JWT 解析时是 Integer，转 Long 容错）。
     */
    private Long toLong(Object uidAttr) {
        if (uidAttr instanceof Long) {
            return (Long) uidAttr;
        }
        if (uidAttr instanceof Number) {
            return ((Number) uidAttr).longValue();
        }
        if (uidAttr instanceof String) {
            try {
                return Long.parseLong((String) uidAttr);
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return null;
    }

    /**
     * 写入 JSON 响应（与 JwtAuthInterceptor 风格一致）。
     *
     * <p>HTTP 状态码 200，业务码由 code 字段表达（403/401），与现有 Result 风格一致。</p>
     */
    private void reject(HttpServletResponse response,
                        int code,
                        String message) throws Exception {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/json;charset=UTF-8");
        String escaped = message.replace("\"", "\\\"");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + escaped + "\",\"data\":null}");
        response.getWriter().flush();
        log.debug("Perm rejected code={} msg={}", code, message);
    }
}
