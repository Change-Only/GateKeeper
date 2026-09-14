package com.gatekeeper.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GatewayPaths 单元测试 — T13 修复「网关对任何接口都回 404」的路径匹配缺陷
 *
 * <p>缺陷回顾：{@code GatewayContext.path} 是含 context-path 的原始 requestURI
 * （如 {@code /api/gateway/test}），而 {@code interface_path} 库里存的是 {@code /test}
 * （部分历史数据是 {@code /gateway/test}）。直接 eq 匹配必然落空。
 * 这里把「候选路径必须同时覆盖两种录入约定，且不需要数据迁移」钉死。</p>
 */
@DisplayName("GatewayPaths：接口路径候选 / 网关入口地址拼接")
class GatewayPathsTest {

    @Test
    @DisplayName("候选路径覆盖「去 context-path」与「再去 /gateway」，兼容两种存量录入约定")
    void candidatesCoverBothConventions() {
        List<String> c = GatewayPaths.interfacePathCandidates("/api/gateway/test", "/api");

        assertEquals("/gateway/test", c.get(0));
        assertEquals("/test", c.get(1), "存量库里 interface_path 多为 /test，必须命中");
        assertTrue(c.contains("/api/gateway/test"), "原始 URI 也要兜住");
    }

    @Test
    @DisplayName("无 context-path 时只有原始 URI（不凭空造候选）")
    void candidatesWithoutContextPath() {
        List<String> c = GatewayPaths.interfacePathCandidates("/gateway/test", null);

        assertEquals("/gateway/test", c.get(0));
        assertEquals("/test", c.get(1));
        assertEquals(2, c.size());
    }

    @Test
    @DisplayName("请求恰好命中 context-path 时不越界（子串截取必须按段边界）")
    void candidatesExactContextPath() {
        List<String> c = GatewayPaths.interfacePathCandidates("/api", "/api");

        assertEquals("/", c.get(0));
    }

    @Test
    @DisplayName("多级路径与尾部斜杠都能归一")
    void candidatesNormalize() {
        List<String> c = GatewayPaths.interfacePathCandidates("/api/gateway/order//create", "/api");

        assertEquals("/gateway/order/create", c.get(0), "重复斜杠应折叠");
        assertEquals("/order/create", c.get(1));
    }

    @Test
    @DisplayName("接口路径里已经带了 /gateway 前缀时不重复拼接")
    void gatewayEntryUrlNoDoublePrefix() {
        assertEquals("http://127.0.0.1:8080/api/gateway/test",
                GatewayPaths.gatewayEntryUrl("http://127.0.0.1:8080", "/api", "/test"));
        assertEquals("http://127.0.0.1:8080/api/gateway/test",
                GatewayPaths.gatewayEntryUrl("http://127.0.0.1:8080/", "/api", "/gateway/test"));
        assertEquals("http://127.0.0.1:8080/gateway/test",
                GatewayPaths.gatewayEntryUrl("http://127.0.0.1:8080", null, "test"));
    }

    @Test
    @DisplayName("候选列表去重且至少有一个元素（不能返回空列表让调用方空转）")
    void candidatesAreDedupedAndNonEmpty() {
        List<String> c = GatewayPaths.interfacePathCandidates("/api/gateway/test", "");
        assertTrue(!c.isEmpty());
        assertEquals(c.size(), c.stream().distinct().count(), "不应出现重复候选");
    }
}
