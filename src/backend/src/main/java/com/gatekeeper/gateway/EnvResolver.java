package com.gatekeeper.gateway;

import com.gatekeeper.gateway.dto.GatewayContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/**
 * 环境码解析器 — 从请求上下文一致地解析部署环境（如 prod / pre / gray）
 *
 * <p>解析优先级：</p>
 * <ol>
 *   <li>请求头 {@code X-Gk-Env}（由调用方 / 网关前置显式指定，优先级最高）；</li>
 *   <li>否则使用全局配置 {@code gatekeeper.env}（默认 {@code prod}）。</li>
 * </ol>
 * <p>设计为无状态小工具，不依赖外部存储；字段默认值保证即便在纯单元测试（无 Spring 注入）下
 * 也回退到 {@code "prod"}，供 {@code VersionRouteHandler} 在 fail-open 场景下安全调用。</p>
 *
 * @author GateKeeper
 * @since T04-B (APIM V2 网关灰度路由)
 */
@Component
public class EnvResolver {

    /** 全局默认环境码；未配置时回退为 prod。字段默认值保证无 Spring 注入时仍可用。 */
    @Value("${gatekeeper.env:prod}")
    private String defaultEnv = "prod";

    /**
     * 解析环境码
     *
     * @param ctx 网关上下文（可空；内部对空值做防御）
     * @return 环境码字符串（永不为 null）
     */
    public String resolve(GatewayContext ctx) {
        if (ctx != null) {
            HttpServletRequest request = ctx.getHttpRequest();
            if (request != null) {
                String headerEnv = request.getHeader("X-Gk-Env");
                if (headerEnv != null && !headerEnv.trim().isEmpty()) {
                    return headerEnv.trim();
                }
            }
        }
        return defaultEnv == null ? "prod" : defaultEnv;
    }
}
