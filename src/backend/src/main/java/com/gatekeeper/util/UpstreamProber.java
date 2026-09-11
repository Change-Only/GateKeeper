package com.gatekeeper.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;

/**
 * 上游连通性探测器 — T03b 接口环境配置「连通性测试」的动作执行者
 *
 * <p>对目标 {@code upstreamUrl} 发起一次轻量 {@code HEAD} 请求（默认 5s 超时），
 * 只要拿到 {@code <500} 的 HTTP 响应即视为「连通」（404/405 也算通，因为服务已在响应）。</p>
 *
 * <p>刻意做成独立组件，便于在 Service 单测中被 mock，避免真实外呼。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Slf4j
@Component
public class UpstreamProber {

    /** 默认探测超时（毫秒） */
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    /** 共享 HttpClient（短连接探测，关闭自动重试） */
    private final CloseableHttpClient httpClient;

    /**
     * 构造探测器并初始化 HttpClient。
     */
    public UpstreamProber() {
        this.httpClient = HttpClients.custom()
                .disableAutomaticRetries()
                .setDefaultRequestConfig(defaultConfig(DEFAULT_TIMEOUT_MS))
                .build();
    }

    /**
     * 探测目标地址是否连通。
     *
     * @param url       目标地址（http:// 或 https:// 开头）
     * @param timeoutMs 超时（毫秒），&lt;=0 时使用默认 5000ms
     * @return true=连通（拿到 &lt;500 的响应）；false=不可达/超时/服务端 5xx
     */
    public boolean probe(String url, int timeoutMs) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        int t = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        HttpHead head = new HttpHead(url.trim());
        head.setConfig(defaultConfig(t));
        try (CloseableHttpResponse response = httpClient.execute(head)) {
            int code = response.getStatusLine().getStatusCode();
            boolean ok = code > 0 && code < 500;
            log.debug("Upstream probe: url={}, status={}, ok={}", url, code, ok);
            return ok;
        } catch (Exception ex) {
            log.debug("Upstream probe failed: url={}, cause={}", url, ex.getMessage());
            return false;
        }
    }

    /**
     * 构建连接/读取/连接请求三超时一致的 RequestConfig。
     */
    private RequestConfig defaultConfig(int timeoutMs) {
        return RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .build();
    }

    /**
     * 释放底层连接资源。
     */
    @PreDestroy
    public void destroy() {
        try {
            httpClient.close();
        } catch (IOException ex) {
            log.warn("Close UpstreamProber httpClient failed: {}", ex.getMessage());
        }
    }
}
