package com.gatekeeper.config;

import com.gatekeeper.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * JWT 鉴权拦截器 — 保护全部管理接口
 *
 * <p>校验请求头中的 Bearer Token，解析通过后将当前登录用户信息写入请求属性
 * （X-USER-ID / X-USERNAME），供 Controller 读取操作人；未携带或令牌无效时
 * 直接返回 401，阻止未认证访问管理后台接口。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    /**
     * 前置校验：提取并验证 Bearer Token
     *
     * @return true 放行；false 中断并写入 401 响应
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行预检请求（CORS OPTIONS）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            reject(response, request, "未登录或登录已过期");
            return false;
        }

        Claims claims = jwtUtil.parseToken(auth.substring(7));
        if (claims == null) {
            reject(response, request, "登录已过期，请重新登录");
            return false;
        }

        // 将当前用户信息写入请求属性，供业务层审计使用
        request.setAttribute("X-USER-ID", claims.get("uid"));
        request.setAttribute("X-USERNAME", claims.getSubject());
        return true;
    }

    /**
     * 返回 401 JSON 响应
     */
    private void reject(HttpServletResponse response, HttpServletRequest request, String message) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + message + "\",\"data\":null}");
        response.getWriter().flush();
        log.warn("JWT auth rejected: uri={}, ip={}, reason={}", request.getRequestURI(), request.getRemoteAddr(), message);
    }
}
