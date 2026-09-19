package com.gatekeeper.gateway;

import com.gatekeeper.gateway.dto.GatewayContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/**
 * 环境码解析器 — 从请求上下文一致地解析部署环境（如 prod / pre / gray）
 *
 * <p>解析优先级（T19 收敛为唯一入口）：</p>
 * <ol>
 *   <li>请求头 {@code X-Gk-Env}（网关自有命名空间，优先级最高）；</li>
 *   <li>否则请求头 {@code X-Env}（早期接入方使用的兼容头，继续支持，零回归）；</li>
 *   <li>否则全局配置 {@code gatekeeper.env}（默认 {@code prod}）。</li>
 * </ol>
 *
 * <p><b>为什么必须收敛到一个入口（T19 修复）</b>：此前环境头有两条互不相干的分支——</p>
 * <ul>
 *   <li>{@code AppAuthHandler}（{@code @Order(1)}）自己读 {@code X-Env} 并<b>先</b>写入 {@code ctx.envCode}；</li>
 *   <li>{@code VersionRouteHandler}（{@code @Order(6)}）再调本类解析 {@code X-Gk-Env}，
 *       但写入语句是「{@code ctx.envCode} 非空则不覆盖」——此时它必非空，
 *       于是 {@code X-Gk-Env} <b>永远不生效</b>（实测确认）。</li>
 * </ul>
 * <p>修复方式：让 {@code AppAuthHandler} 也改为调用本类，两个头在同一处按明确优先级解析，
 * 不再存在「谁先写谁赢」的隐式竞争。</p>
 *
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

    /** 网关自有环境头（优先级最高） */
    public static final String HEADER_ENV_PREFERRED = "X-Gk-Env";
    /** 兼容环境头（早期接入方使用，T19 起仍有效，但优先级低于 X-Gk-Env） */
    public static final String HEADER_ENV_LEGACY = "X-Env";

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
                String preferred = trimToNull(request.getHeader(HEADER_ENV_PREFERRED));
                if (preferred != null) {
                    return preferred;
                }
                String legacy = trimToNull(request.getHeader(HEADER_ENV_LEGACY));
                if (legacy != null) {
                    return legacy;
                }
            }
        }
        return defaultEnv == null ? "prod" : defaultEnv;
    }

    /**
     * 去空白并归一空串为 null。
     *
     * @param raw 原始头值
     * @return 去空白后的值；null / 空白串返回 null
     */
    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
