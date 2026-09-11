package com.gatekeeper.gateway.handler;

import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Step 8: 请求转发至后端服务
 *
 * <p>责任链第 8 环：将解密后的请求体转发到接口配置的后端真实服务地址，
 * 连接/读取超时取接口配置（默认 5000ms）。</p>
 *
 * <p>性能优化：底层使用 Apache HttpClient 连接池（全局共享），
 * 并按超时值缓存 RestTemplate，避免每次请求新建连接带来的握手与 GC 开销。</p>
 * <ul>
 *   <li>后端超时 → 返回 504</li>
 *   <li>后端连接失败/异常 → 返回 502</li>
 * </ul>
 */
@Slf4j
@Component
@Order(8)
public class ForwardHandler implements GatewayHandler {

    /** 转发连接池：全局共享，避免每次请求新建 TCP 连接 */
    private final PoolingHttpClientConnectionManager connectionManager;
    /** 共享 HttpClient（复用连接池） */
    private final CloseableHttpClient httpClient;
    /** 按超时值缓存 RestTemplate（同一超时值的请求复用同一实例） */
    private final ConcurrentHashMap<Integer, RestTemplate> templateCache = new ConcurrentHashMap<>();

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
     * 转发请求到后端服务并回填响应
     *
     * @param ctx 网关上下文（需已写入 backendUrl、decryptedBody、timeoutMs；响应写入 responseBody/responseStatus）
     */
    @Override
    public void handle(GatewayContext ctx) {
        String backendUrl = ctx.getBackendUrl();
        // 优先转发解密后的明文，无解密配置则转发原始请求体
        String body = ctx.getDecryptedBody() != null ? ctx.getDecryptedBody() : ctx.getRequestBody();
        int timeout = ctx.getTimeoutMs() != null ? ctx.getTimeoutMs() : 5000;

        try {
            // 复用共享连接池的 RestTemplate（按超时值缓存）
            RestTemplate restTemplate = getRestTemplate(timeout);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            // 以原始方法（GET/POST/PUT/DELETE）转发到后端服务
            ResponseEntity<String> response = restTemplate.exchange(
                    backendUrl,
                    HttpMethod.valueOf(ctx.getMethod()),
                    entity,
                    String.class
            );

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
     * 按超时值获取复用的 RestTemplate
     * <p>连接池全局共享，仅超时设置按实例区分（接口可配置不同超时）</p>
     *
     * @param timeout 连接/读取超时（毫秒）
     * @return 该超时值对应的 RestTemplate
     */
    private RestTemplate getRestTemplate(int timeout) {
        return templateCache.computeIfAbsent(timeout, t -> {
            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
            factory.setConnectTimeout(t);
            factory.setReadTimeout(t);
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
