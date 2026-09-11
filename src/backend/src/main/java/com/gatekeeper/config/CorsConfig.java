package com.gatekeeper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域（CORS）配置 — 仅允许配置中指定的来源跨域访问
 *
 * <p>安全：不允许使用通配符 * 搭配凭证；来源列表通过
 * {@code gatekeeper.cors.allowed-origins} 配置（逗号分隔），默认仅本机开发端口。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 允许跨域的来源列表（逗号分隔，配置注入） */
    @Value("${gatekeeper.cors.allowed-origins:http://localhost:8081}")
    private String allowedOrigins;

    /**
     * 注册全局跨域规则
     *
     * @param registry 跨域注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
