package com.gatekeeper.gateway.dto;

import lombok.Data;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 网关请求上下文 - 贯穿整个责任链
 *
 * <p>一次网关请求的全部状态都存放在本对象中，由各 Handler 依次读写：
 * 前序 Handler 写入校验结果（如 appId、interfaceId、解密后的请求体），
 * 后序 Handler 读取并继续处理，最后由 LogHandler 汇总写入调用日志。</p>
 */
@Data
public class GatewayContext {

    /** 原始 HTTP 请求对象 */
    private HttpServletRequest httpRequest;
    /** 请求路径（/gateway/xxx） */
    private String path;
    /** 请求方法（GET/POST/PUT/DELETE） */
    private String method;
    /** 客户端真实 IP（优先取 X-Forwarded-For 最后一跳） */
    private String clientIp;

    // ==================== 应用信息 ====================
    /** 应用 ID（AppAuthHandler 校验后写入） */
    private Long appId;
    /** 应用 Key（来自请求头 X-App-Key） */
    private String appKey;
    /** 应用名称 */
    private String appName;
    /** 应用密钥（用于签名校验） */
    private String appSecret;
    /** 应用状态（1=启用 0=停用 2=已过期） */
    private Integer appStatus;

    // ==================== 接口信息 ====================
    /** 匹配到的接口 ID */
    private Long interfaceId;
    /** 接口路径（已注册的接口路径） */
    private String interfacePath;
    /** 后端真实服务地址（转发目标） */
    private String backendUrl;
    /** 接口超时时间（毫秒） */
    private Integer timeoutMs;

    // ==================== 加解密信息 ====================
    /** 本次调用实际使用的加密算法（NONE/SM4/AES，用于日志记录） */
    private String encryptionAlgorithm = "NONE";
    /** 入参加密算法 */
    private String requestAlgorithm;
    /** 入参加密模式（ECB/CBC/CFB/OFB/CTR） */
    private String requestMode;
    /** 入参加密密钥（Base64） */
    private String requestKey;
    /** 入参加密 IV 向量（Base64） */
    private String requestIv;
    /** 入参加密填充方式 */
    private String requestPadding;
    /** 是否启用入参加密 */
    private boolean requestEncrypted;
    /** 返参加密算法 */
    private String responseAlgorithm;
    /** 返参加密模式 */
    private String responseMode;
    /** 返参加密密钥（Base64） */
    private String responseKey;
    /** 返参加密 IV 向量（Base64） */
    private String responseIv;
    /** 返参加密填充方式 */
    private String responsePadding;
    /** 是否启用返参加密 */
    private boolean responseEncrypted;

    // ==================== 请求参数 ====================
    /** 原始请求体（可能是密文） */
    private String requestBody;
    /** 解密后的请求体明文（转发给后端服务的内容） */
    private String decryptedBody;

    // ==================== 响应信息 ====================
    /** 后端服务返回的响应体明文 */
    private String responseBody;
    /** 后端服务返回的 HTTP 状态码 */
    private Integer responseStatus;
    /** 加密后的响应体（返回给调用方的内容） */
    private String encryptedResponseBody;

    // ==================== 日志信息 ====================
    /** 请求开始时间戳（毫秒，用于计算耗时） */
    private long startTime;
    /** 处理总耗时（毫秒） */
    private int costTime;
    /** 是否被限流 */
    private boolean rateLimited;
    /** 是否被拦截 */
    private boolean blocked;
    /** 拦截原因 */
    private String blockReason;

    // ==================== 认证信息 ====================
    /** 请求签名（请求头 X-Signature） */
    private String signature;
    /** 请求时间戳（请求头 X-Timestamp，防重放） */
    private long timestamp;
    /** 随机数（请求头 X-Nonce，防重放） */
    private String nonce;
    /** 是否认证成功 */
    private boolean authSuccess;

    // ==================== 版本路由 / 灰度（T04-B） ====================
    /** 目标版本号（VersionRouteHandler 灰度分流后写入，如 v1/v2；默认空） */
    private String version;
    /** 环境码（VersionRouteHandler 解析后写入，如 prod/pre/gray；默认空，回退 prod） */
    private String envCode;
    /** 链路追踪 ID（全链路透传，供日志/观测使用；默认空） */
    private String traceId;
    /** 上游（后端服务）耗时（毫秒，ForwardHandler 回填；默认 0） */
    private int upstreamCost;
    /** 认证耗时（毫秒，AppAuthHandler 回填，可选；默认 0） */
    private int authCost;
    // 注：以上字段由 Lombok @Data 自动生成 getter/setter，
    // VersionRouteHandler 通过 ctx.setVersion(...) / ctx.setEnvCode(...) 填充。

    /**
     * 从 HTTP 请求构建网关上下文（默认不信任代理头）：
     * 提取路径、方法、客户端 IP、以及 X-App-Key / X-Signature / X-Timestamp / X-Nonce 认证请求头
     *
     * @param request 原始 HTTP 请求
     * @return 初始化完成的网关上下文
     */
    public static GatewayContext from(HttpServletRequest request) {
        return from(request, false);
    }

    /**
     * 从 HTTP 请求构建网关上下文（可指定是否信任代理头）
     *
     * <p>IP 获取策略：默认取 RemoteAddr（防 X-Forwarded-For 伪造）；
     * 仅当网关部署在可信反向代理之后（trustXff=true）时才取 X-Forwarded-For 最后一跳。</p>
     *
     * @param request  原始 HTTP 请求
     * @param trustXff 是否信任 X-Forwarded-For 头
     * @return 初始化完成的网关上下文
     */
    public static GatewayContext from(HttpServletRequest request, boolean trustXff) {
        GatewayContext ctx = new GatewayContext();
        ctx.setHttpRequest(request);
        ctx.setPath(request.getRequestURI());
        ctx.setMethod(request.getMethod());
        ctx.setStartTime(System.currentTimeMillis());
        ctx.setRateLimited(false);
        ctx.setBlocked(false);
        ctx.setAuthSuccess(false);

        // 获取客户端 IP：仅当信任代理时才解析 X-Forwarded-For，否则使用直连地址
        if (trustXff) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isEmpty()) {
                String[] parts = ip.split(",");
                ctx.setClientIp(parts[parts.length - 1].trim());
            } else {
                ctx.setClientIp(request.getRemoteAddr());
            }
        } else {
            ctx.setClientIp(request.getRemoteAddr());
        }

        // 提取认证请求头
        ctx.setAppKey(request.getHeader("X-App-Key"));
        ctx.setSignature(request.getHeader("X-Signature"));
        String ts = request.getHeader("X-Timestamp");
        if (ts != null && !ts.isEmpty()) {
            try {
                ctx.setTimestamp(Long.parseLong(ts));
            } catch (NumberFormatException e) {
                // 非法时间戳视为缺失（timestamp=0），由后续鉴权强制校验返回 401
                ctx.setTimestamp(0);
            }
        }
        ctx.setNonce(request.getHeader("X-Nonce"));

        return ctx;
    }
}
