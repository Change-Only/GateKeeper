package com.gatekeeper.config;

import com.gatekeeper.security.PermissionInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 — 注册 JWT 鉴权拦截器 + 权限点拦截器
 *
 * <p>应用配置 context-path=/api，Spring MVC 的拦截器路径匹配基于去掉 context-path
 * 之后的路径，因此这里不能再写 /api 前缀，否则规则永远匹配不上，管理接口将处于零鉴权状态。</p>
 *
 * <p>执行顺序：Spring 拦截器按注册顺序执行。先注册 JwtAuthInterceptor 完成登录态认证
 * 并把 X-USER-ID 写入请求属性；后注册 PermissionInterceptor 读取该属性进行权限点校验。</p>
 *
 * <p>放行路径：</p>
 * <ul>
 *   <li>/auth/login — 登录接口（获取令牌的唯一入口）</li>
 *   <li>/gateway/** — 网关代理入口（自有 AppKey+签名 鉴权链路，不走 JWT）</li>
 *   <li>/doc.html 及 Knife4j 静态资源 — 接口文档（生产环境建议关闭）</li>
 *   <li>/error — Spring Boot 错误转发路径</li>
 *   <li>/sys/menu/list 与 /sys/menu/perm-points — 权限点查询（无需授权，T02 兼容）</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;
    private final PermissionInterceptor permissionInterceptor;

    /**
     * 注册鉴权拦截器并配置放行路径
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1) 先注册：JWT 鉴权（登录态校验）
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/gateway/**",
                        "/doc.html",
                        "/webjars/**",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/error"
                );

        // 2) 后注册：权限点拦截（依赖 X-USER-ID 已写入）
        // 仅拦截 /api/** 子路径（即去掉 context-path 后的全部管理接口）
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 公开路径（与 JwtAuthInterceptor 一致，避免在登录前被拦截）
                        "/auth/login",
                        "/gateway/**",
                        "/doc.html",
                        "/webjars/**",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/error",
                        // 权限点查询列表无需权限即可获取（前端需先看到权限点才能决定授权）
                        "/sys/menu/list",
                        "/sys/menu/perm-points"
                );
    }
}
