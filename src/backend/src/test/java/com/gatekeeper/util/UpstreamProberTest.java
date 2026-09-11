package com.gatekeeper.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UpstreamProber 单测 —— 使用本地 JDK HttpServer 作为 mock 上游，避免真实外呼。
 */
@DisplayName("UpstreamProber 连通性探测")
class UpstreamProberTest {

    private HttpServer server;
    private int port;
    private UpstreamProber prober;

    @BeforeEach
    void setUp() throws IOException {
        // 端口 0 → 由系统分配空闲端口
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/err", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.createContext("/notfound", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
        prober = new UpstreamProber();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        prober.destroy();
    }

    @Test
    @DisplayName("200 → 连通")
    void probe_ok() {
        assertTrue(prober.probe("http://127.0.0.1:" + port + "/ok", 2000));
    }

    @Test
    @DisplayName("404 → 服务在响应，视为连通")
    void probe_notFoundStillReachable() {
        assertTrue(prober.probe("http://127.0.0.1:" + port + "/notfound", 2000));
    }

    @Test
    @DisplayName("500 → 服务端异常，视为不连通")
    void probe_serverError() {
        assertFalse(prober.probe("http://127.0.0.1:" + port + "/err", 2000));
    }

    @Test
    @DisplayName("空地址 → false")
    void probe_blank() {
        assertFalse(prober.probe("", 2000));
        assertFalse(prober.probe(null, 2000));
    }

    @Test
    @DisplayName("不可达地址 → false（超时/拒绝）")
    void probe_unreachable() {
        // 127.0.0.1 上大概率无 1 端口监听
        assertFalse(prober.probe("http://127.0.0.1:1/nope", 800));
    }
}
