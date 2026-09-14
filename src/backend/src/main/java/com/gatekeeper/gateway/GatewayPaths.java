package com.gatekeeper.gateway;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 网关路径工具 — T13 修复「接口路径永远匹配不上」的缺陷
 *
 * <p><b>缺陷（2026-09-14 实测代码路径确认）</b>：
 * {@code GatewayContext.from} 用的是 {@code request.getRequestURI()}，它**含 context-path**，
 * 若按 {@code /api/gateway/test} 访问，则 {@code ctx.path = "/api/gateway/test"}；
 * 而 {@code PermissionHandler} 拿它去 {@code eq("interface_path", ...)} 查库。
 * 库里 {@code interface_path} 存的是 {@code /test}（前端表单 placeholder 提示的是 {@code /gateway/xxx}）,
 * ⇒ **两种写法都匹配不上**，网关只会回 404「接口不存在或未启用」。
 *
 * <p><b>修法（向后兼容，不强制改存量数据）</b>：按优先级生成候选值，逐个尝试匹配：
 * <ol>
 *   <li>去掉 context-path：{@code /gateway/test}（与 {@code ApiInterface.interfacePath} 的注释「/gateway/ 开头」一致）</li>
 *   <li>再去掉 {@code /gateway} 前缀：{@code /test}（与前端表单 placeholder 之外的实际录入习惯、以及存量数据一致）</li>
 *   <li>原始 URI：{@code /api/gateway/test}（兜住极端历史录入）</li>
 * </ol>
 * 一、二条覆盖了现存两种约定，因此本修复**不需要任何数据迁移**。</p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
public final class GatewayPaths {

    /** 网关统一入口前缀 */
    public static final String GATEWAY_PREFIX = "/gateway";

    private GatewayPaths() {
    }

    /**
     * 生成 {@code interface_path} 的候选匹配值（有序：先精确，后宽松）。
     *
     * @param requestUri  原始 requestURI，如 {@code /api/gateway/test}
     * @param contextPath 应用 context-path，如 {@code /api}
     * @return 去重后的候选列表（至少含 1 个元素）
     */
    public static List<String> interfacePathCandidates(String requestUri, String contextPath) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String uri = normalize(requestUri);
        String cp = normalize(contextPath);

        // ① 去掉 context-path
        String noCtx = uri;
        if (!cp.isEmpty()) {
            if (uri.equals(cp)) {
                noCtx = "/";
            } else if (uri.startsWith(cp + "/")) {
                noCtx = uri.substring(cp.length());
            }
        }
        out.add(noCtx);

        // ② 再去掉 /gateway 前缀
        if (noCtx.startsWith(GATEWAY_PREFIX + "/")) {
            out.add(noCtx.substring(GATEWAY_PREFIX.length()));
        } else if (noCtx.equals(GATEWAY_PREFIX)) {
            out.add("/");
        }

        // ③ 原始 URI 兜底
        out.add(uri);
        return new ArrayList<>(out);
    }

    /**
     * 拼接「走网关」的完整请求地址（供接口测试用）。
     *
     * @param baseUrl       服务自身基址，如 {@code http://127.0.0.1:8080}
     * @param contextPath   应用 context-path，如 {@code /api}
     * @param interfacePath 接口路径，如 {@code /test} 或 {@code /gateway/test}
     * @return 形如 {@code http://127.0.0.1:8080/api/gateway/test}
     */
    public static String gatewayEntryUrl(String baseUrl, String contextPath, String interfacePath) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        String cp = normalize(contextPath);
        String path = normalize(interfacePath);
        // 接口路径里已经带了 /gateway 前缀时不要重复拼
        String suffix = path.startsWith(GATEWAY_PREFIX + "/") || path.equals(GATEWAY_PREFIX)
                ? path
                : GATEWAY_PREFIX + path;
        return base + (cp.isEmpty() ? "" : cp) + suffix;
    }

    /**
     * 归一化路径：空 ⇒ ""；补前导 {@code /}；折叠重复斜杠；去掉尾部 {@code /}（根路径除外）。
     */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replaceAll("/{2,}", "/");
        if (s.isEmpty()) {
            return "";
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
