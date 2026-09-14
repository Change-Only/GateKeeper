package com.gatekeeper.gateway.handler;

import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.EnvConfigResolver;
import com.gatekeeper.gateway.dto.EffectiveEnvConfig;
import com.gatekeeper.gateway.dto.GatewayContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Step 8: 请求转发至后端服务 / 或按配置返回 Mock
 *
 * <p>责任链第 8 环：把（已解密的）请求体转发到**生效环境配置**解析出的后端地址。</p>
 *
 * <p><b>T13 改造（这是"环境配置形同虚设"的修复点）</b>：
 * 此前本处理器只用 {@code ctx.getBackendUrl()}（即 {@code api_interface.backend_url}），
 * 环境配置表 {@code api_env_config} 里的服务前缀/连接超时/读取超时/重试/Mock
 * <b>一个都没有被读取</b> —— 用户在界面上配了、切换了 Mock，网关完全不理，
 * 这正是「开启 Mock 不起作用」的根因。现在统一改为：
 * <ol>
 *   <li>目标地址：生效配置有服务前缀 ⇒ {@code 前缀 + 接口URI}；否则回退接口默认后端地址；</li>
 *   <li>超时：生效配置的 connectTimeout / readTimeout（缺失时回退接口自身 timeoutMs / 5000ms）；</li>
 *   <li>重试：生效配置的 retryCount（仅对**连接/读取异常**重试，不对 4xx/5xx 重试，
 *       避免把"后端返回了错误"误当成"没打通"而重复写库）；</li>
 *   <li><b>Mock 短路</b>：生效配置 mockEnabled=1 时**不转发**，直接返回配置的状态码与报文。</li>
 * </ol>
 * 生效配置由 {@code EnvConfigResolver} 解析（接口级覆盖 &gt; 分组继承 &gt; 接口默认），
 * 与前端只读预览共用同一口径。</p>
 *
 * <p>性能：底层使用 Apache HttpClient 连接池（全局共享），并按「连接超时:读取超时」缓存 RestTemplate，
 * 避免每次请求新建连接带来的握手与 GC 开销。</p>
 * <ul>
 *   <li>后端超时 → 返回 504</li>
 *   <li>后端连接失败/异常 → 返回 502</li>
 * </ul>
 */
@Slf4j
@Component
@Order(8)
public class ForwardHandler implements GatewayHandler {

    /** 未配置任何超时时的默认值（毫秒） */
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    /** 转发连接池：全局共享，避免每次请求新建 TCP 连接 */
    private final PoolingHttpClientConnectionManager connectionManager;
    /** 共享 HttpClient（复用连接池） */
    private final CloseableHttpClient httpClient;
    /** 按「连接:读取」超时值缓存 RestTemplate（同一组合的请求复用同一实例） */
    private final ConcurrentHashMap<String, RestTemplate> templateCache = new ConcurrentHashMap<>();

    /**
     * 生效环境配置解析器。
     *
     * <p>正常路径下上下文里已带（PermissionHandler 解析后写入），此处只作为**兜底**：
     * 缺省依赖不阻断转发（fail-open，回退到改造前的行为）。</p>
     */
    @Autowired(required = false)
    private EnvConfigResolver envConfigResolver;

    /**
     * 构造转发处理器，初始化转发连接池
     *
     * @param maxTotal    连接池最大连接数
     * @param maxPerRoute 单个后端路由最大连接数
     */
    public ForwardHandler(
            @Value("${gatekeeper.gateway.pool-max-total:500}") int maxTotal,
            @Value("${gatekeeper.gateway.pool-max-per-route:100}") int maxPerRoute) {
        this.connectionManager = new PoolingHttpClientConnectionManager();
        this.connectionManager.setMaxTotal(maxTotal);
        this.connectionManager.setDefaultMaxPerRoute(maxPerRoute);
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .build();
        log.info("Forward connection pool initialized: maxTotal={}, maxPerRoute={}", maxTotal, maxPerRoute);
    }

    /**
     * 转发请求到后端服务（或按 Mock 配置短路返回）并回填响应
     *
     * @param ctx 网关上下文（需已写入 backendUrl、decryptedBody、timeoutMs；响应写入 responseBody/responseStatus）
     */
    @Override
    public void handle(GatewayContext ctx) {
        EffectiveEnvConfig effective = resolveEffective(ctx);

        // ============ Mock 短路：不转发，直接返回配置的报文 ============
        if (effective != null && effective.isMockOn()) {
            String mockBody = buildMockBody(effective, ctx);
            ctx.setResponseStatus(effective.getMockStatus() != null ? effective.getMockStatus() : 200);
            ctx.setResponseBody(mockBody);
            // 标记"这是 Mock 响应"：GatewayController 据此把配置的状态码透传给调用方
            // （真实转发仍保持既有 200 语义，不顺手改存量行为）
            ctx.setMockResponse(true);
            log.info("Forward skipped by Mock: interface={}, env={}, mockStatus={}, source={}",
                    ctx.getInterfacePath(), effective.getEnvCode(), ctx.getResponseStatus(), effective.getSourceType());
            return;
        }

        String backendUrl = ctx.getBackendUrl();
        // 优先转发解密后的明文，无解密配置则转发原始请求体
        String body = ctx.getDecryptedBody() != null ? ctx.getDecryptedBody() : ctx.getRequestBody();

        int connectTimeout = pickTimeout(effective == null ? null : effective.getConnectTimeout(), ctx.getTimeoutMs());
        int readTimeout = pickTimeout(effective == null ? null : effective.getReadTimeout(), ctx.getTimeoutMs());
        int retry = effective != null && effective.getRetryCount() != null && effective.getRetryCount() > 0
                ? effective.getRetryCount() : 0;

        try {
            RestTemplate restTemplate = getRestTemplate(connectTimeout, readTimeout);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            // 以原始方法（GET/POST/PUT/DELETE）转发到后端服务；retryCount>0 时对连接/读取异常重试
            ResponseEntity<String> response = null;
            int attempt = 0;
            while (true) {
                try {
                    response = restTemplate.exchange(
                            backendUrl,
                            HttpMethod.valueOf(ctx.getMethod()),
                            entity,
                            String.class
                    );
                    break;
                } catch (ResourceAccessException e) {
                    if (attempt >= retry) {
                        throw e;
                    }
                    attempt++;
                    log.warn("Forward failed, retrying ({}/{}): interface={}, url={}, cause={}",
                            attempt, retry, ctx.getInterfacePath(), backendUrl, e.getMessage());
                }
            }

            ctx.setResponseBody(response.getBody());
            ctx.setResponseStatus(response.getStatusCodeValue());

            log.debug("Forward success: backend={}, status={}", backendUrl, response.getStatusCodeValue());
        } catch (ResourceAccessException e) {
            // 连接/读取超时或连接拒绝
            log.error("Forward timeout: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                throw GatewayException.gatewayTimeout("后端服务超时");
            }
            throw GatewayException.badGateway("后端服务连接失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Forward failed: {}", e.getMessage());
            ctx.setResponseStatus(500);
            ctx.setResponseBody("{\"error\":\"后端服务异常\"}");
            throw GatewayException.badGateway("后端服务异常: " + e.getMessage());
        }
    }

    /**
     * 取生效配置：优先用上下文里已解析好的（PermissionHandler 写入），没有则自行解析。
     *
     * @param ctx 网关上下文
     * @return 生效配置；解析器不可用时返回 null（回退改造前行为）
     */
    private EffectiveEnvConfig resolveEffective(GatewayContext ctx) {
        if (ctx.getEffectiveEnvConfig() != null) {
            return ctx.getEffectiveEnvConfig();
        }
        if (envConfigResolver == null) {
            return null;
        }
        try {
            EffectiveEnvConfig e = envConfigResolver.resolve(ctx.getInterfaceId(), ctx.getEnvCode());
            ctx.setEffectiveEnvConfig(e);
            return e;
        } catch (Exception ex) {
            // 解析失败不能阻断转发（fail-open），否则一次脏数据就把流量全掐了
            log.warn("Resolve effective env config failed, fallback to interface backendUrl: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 超时取值：生效配置 → 接口自身 timeoutMs → 默认 5000ms。
     */
    private int pickTimeout(Integer fromConfig, Integer fromInterface) {
        if (fromConfig != null && fromConfig > 0) {
            return fromConfig;
        }
        if (fromInterface != null && fromInterface > 0) {
            return fromInterface;
        }
        return DEFAULT_TIMEOUT_MS;
    }

    /**
     * 构造 Mock 返回体：配置了 mockResponse 就原样返回，否则给一段**自解释**的提示 JSON。
     *
     * <p>刻意带上 interface / env / source 三个字段：调用方一眼能看出"这是 Mock 的响应、
     * 哪个环境的、配置来自哪里"，避免把 Mock 报文误当真实业务数据。</p>
     */
    private String buildMockBody(EffectiveEnvConfig effective, GatewayContext ctx) {
        if (effective.getMockResponse() != null && !effective.getMockResponse().trim().isEmpty()) {
            return effective.getMockResponse();
        }
        String path = escape(ctx.getInterfacePath());
        String env = escape(effective.getEnvCode());
        String source = escape(effective.getSourcePath() != null ? effective.getSourcePath() : effective.getSourceType());
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "{\"mock\":true,\"interface\":\"" + path + "\",\"env\":\"" + env
                + "\",\"source\":\"" + source + "\",\"message\":\"响应来自 Mock，未转发后端服务\",\"time\":\"" + ts + "\"}";
    }

    /** 极简 JSON 字符串转义（只用于我们自己的字段，避免引入额外依赖） */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 按「连接超时:读取超时」组合获取复用的 RestTemplate
     * <p>连接池全局共享，仅超时设置按实例区分（不同环境/接口可配不同超时）</p>
     *
     * @param connectTimeout 连接超时（毫秒）
     * @param readTimeout    读取超时（毫秒）
     * @return 该超时组合对应的 RestTemplate
     */
    private RestTemplate getRestTemplate(int connectTimeout, int readTimeout) {
        String key = connectTimeout + ":" + readTimeout;
        return templateCache.computeIfAbsent(key, k -> {
            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(connectTimeout);
            factory.setReadTimeout(readTimeout);
            return new RestTemplate(factory);
        });
    }

    /**
     * 应用关闭时释放连接池资源
     */
    @PreDestroy
    public void destroy() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.warn("Close httpClient failed: {}", e.getMessage());
        }
        connectionManager.close();
    }
}
