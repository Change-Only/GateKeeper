package com.gatekeeper.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
 *   <li>handler 是 HandlerMethod 无注解 → 直接放行（兼容存量接口）</li>
 *   <li>非 HandlerMethod（如静态资源 handler）→ 放行</li>
 * </ul>
 * </p>
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
            // 无注解 → 不拦截（兼容存量）
            return true;
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
            log.error("@RequirePerm.value() is empty for handler={}",
                    handlerMethod.getMethod().getName());
            return true; // 错误配置时放行（fail-open 由运维修复）
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
