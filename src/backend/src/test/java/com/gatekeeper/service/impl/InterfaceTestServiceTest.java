package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.dto.InterfaceTestRequest;
import com.gatekeeper.dto.InterfaceTestResult;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.App;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.EnvConfigResolver;
import com.gatekeeper.gateway.dto.EffectiveEnvConfig;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.mapper.AppApiPermissionMapper;
import com.gatekeeper.mapper.AppMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * InterfaceTestServiceImpl 单元测试 — T13「新增接口测试功能」
 *
 * <p>两种模式都要验：DIRECT 用来回答"上游通不通、前缀配得对不对"，
 * GATEWAY 用来回答"这个应用到底能不能调通这个接口"。这里不去真连后端
 * （统一用不可达地址 127.0.0.1:1），只验证**结构性契约**：</p>
 * <ul>
 *   <li>DIRECT 的目标地址 = 生效服务前缀 + 接口 URI，超时取生效配置；</li>
 *   <li>DIRECT 模式下没有前缀时回退接口自身 backend_url，都没有则给出可读错误；</li>
 *   <li>GATEWAY 的目标地址指向 /gateway/**，带齐 X-App-Key / X-Signature / X-Timestamp / X-Nonce / X-Env；</li>
 *   <li>签名按 SM3(AppKey + AppSecret明文 + Timestamp + Nonce) 生成，且页面上只回显前 8 位（打码）；</li>
 *   <li>走网关时找不到任何应用要给出**可读的**错误，而不是 500；</li>
 *   <li>网络层失败要带回 error 而不是抛异常（试调工具的价值就在于"失败也要把过程带回来"）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InterfaceTestService：直连/走网关双模式")
class InterfaceTestServiceTest {

    @Mock
    private ApiInterfaceMapper apiInterfaceMapper;
    @Mock
    private AppMapper appMapper;
    @Mock
    private AppApiGrantMapper appApiGrantMapper;
    @Mock
    private AppApiPermissionMapper appApiPermissionMapper;
    @Mock
    private CryptoService cryptoService;
    @Mock
    private EnvConfigResolver envConfigResolver;

    private InterfaceTestServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new InterfaceTestServiceImpl(
                apiInterfaceMapper, appMapper, appApiGrantMapper, appApiPermissionMapper,
                cryptoService, envConfigResolver);
        set("aesDbKey", "0123456789abcdef0123456789abcdef");
        set("selfBaseUrl", "http://127.0.0.1:1");
    }

    // ===================== DIRECT =====================

    @Test
    @DisplayName("DIRECT：目标 = 生效服务前缀 + 接口 URI，超时取生效配置，网络失败带回 error 不抛异常")
    void directUsesEffectivePrefix() {
        when(apiInterfaceMapper.selectById(20L)).thenReturn(api(20L, 2L, "/test", "GET"));
        when(envConfigResolver.resolve(any(ApiInterface.class), anyString()))
                .thenReturn(effective("http://127.0.0.1:1", 400, 400, 1));

        InterfaceTestResult r = service.test(20L, request("DIRECT", "prod", null), null);

        assertEquals("DIRECT", r.getMode());
        assertEquals("http://127.0.0.1:1/test", r.getTargetUrl(), "前缀去尾斜杠 + URI 补头斜杠");
        assertEquals(Integer.valueOf(400), r.getConnectTimeout());
        assertEquals(Integer.valueOf(400), r.getReadTimeout());
        assertEquals(Integer.valueOf(1), r.getRetryCount());
        assertNotNull(r.getError(), "连不上上游时应带回可读的 error，而不是抛异常");
        assertTrue(!r.isSuccess());
    }

    @Test
    @DisplayName("DIRECT：无服务前缀 → 回退接口自身 backend_url，并留下说明")
    void directFallsBackToInterfaceBackendUrl() {
        when(apiInterfaceMapper.selectById(20L)).thenReturn(api(20L, 2L, "/test", "GET"));
        EffectiveEnvConfig def = EffectiveEnvConfig.fallback("dev");
        when(envConfigResolver.resolve(any(ApiInterface.class), anyString())).thenReturn(def);

        InterfaceTestResult r = service.test(20L, request("DIRECT", "dev", null), null);

        assertEquals("http://127.0.0.1:1", r.getTargetUrl());
        assertEquals("DEFAULT", r.getSourceType());
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("回退")), "回退行为必须显式告知用户");
    }

    @Test
    @DisplayName("DIRECT：既无前缀也无后端地址 → 给出可操作的中文错误（指引去分组侧配置）")
    void directWithoutAnyTarget() {
        ApiInterface a = api(20L, 2L, "/test", "GET");
        a.setBackendUrl(null);
        when(apiInterfaceMapper.selectById(20L)).thenReturn(a);
        when(envConfigResolver.resolve(any(ApiInterface.class), anyString()))
                .thenReturn(EffectiveEnvConfig.fallback("dev"));

        InterfaceTestResult r = service.test(20L, request("DIRECT", "dev", null), null);

        assertNotNull(r.getError());
        assertTrue(r.getError().contains("无法确定目标地址"));
    }

    @Test
    @DisplayName("DIRECT：Mock 开启时仍会真实发请求（试调要绕过策略看上游本身）")
    void directIgnoresMock() {
        when(apiInterfaceMapper.selectById(20L)).thenReturn(api(20L, 2L, "/test", "GET"));
        EffectiveEnvConfig eff = effective("http://127.0.0.1:1", 400, 400, 0);
        eff.setMockEnabled(1);
        when(envConfigResolver.resolve(any(ApiInterface.class), anyString())).thenReturn(eff);

        InterfaceTestResult r = service.test(20L, request("DIRECT", "prod", null), null);

        assertNotNull(r.getError(), "Mock 开着也照样连上游 → 连不上就报错（而不是返回 Mock 报文）");
        assertEquals("http://127.0.0.1:1/test", r.getTargetUrl());
        assertTrue(r.isMock(), "但仍要在结果里标出该环境开了 Mock");
    }

    // ===================== GATEWAY =====================

    @Test
    @DisplayName("GATEWAY：目标指向 /gateway/**，签名头齐备，签名在页面上只露前 8 位")
    void gatewayBuildsSignedRequest() {
        when(apiInterfaceMapper.selectById(20L)).thenReturn(api(20L, 2L, "/test", "GET"));
        when(envConfigResolver.resolve(any(ApiInterface.class), anyString()))
                .thenReturn(effective("http://upstream:8080", 1000, 1000, 0));
        when(appMapper.selectById(14L)).thenReturn(app(14L, "测试应用", "KEY123", "ENCSECRET"));
        when(cryptoService.decrypt(eq("AES"), eq("ENCSECRET"), anyString(), any(), anyString(), anyString()))
                .thenReturn("PLAINSECRET");
        when(cryptoService.digest(eq("SM3"), anyString(), any())).thenReturn("abcdefghijklmnop");

        InterfaceTestResult r = service.test(20L, request("GATEWAY", "prod", 14L), null);

        assertEquals("GATEWAY", r.getMode());
        assertEquals("http://127.0.0.1:1/gateway/test", r.getTargetUrl(), "走网关要走统一入口，而不是上游地址");
        assertEquals(Long.valueOf(14L), r.getAppId());
        assertEquals("测试应用", r.getAppName());
        assertEquals("KEY123", r.getRequestHeaders().get("X-App-Key"));
        assertEquals("abcdefgh***", r.getRequestHeaders().get("X-Signature"), "可用签名不应完整留在页面上");
        assertEquals("prod", r.getRequestHeaders().get("X-Env"));
        assertNotNull(r.getRequestHeaders().get("X-Timestamp"));
        assertNotNull(r.getRequestHeaders().get("X-Nonce"));
        assertTrue(r.getReadTimeout() >= 10000, "网关链路更长，读超时要给足余量");
    }

    @Test
    @DisplayName("GATEWAY：未指定应用 → 自动挑「有有效授权的应用」")
    void gatewayAutoPicksGrantedApp() {
        when(apiInterfaceMapper.selectById(20L)).thenReturn(api(20L, 2L, "/test", "GET"));
        when(envConfigResolver.resolve(any(ApiInterface.class), anyString()))
                .thenReturn(effective("http://upstream:8080", 1000, 1000, 0));

        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        g.setAppId(7L);
        g.setApiId(20L);
        g.setEnvCode("prod");
        g.setStatus(1);
        when(appApiGrantMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(g));
        when(appMapper.selectById(7L)).thenReturn(app(7L, "已授权应用", "K7", "S7"));
        when(cryptoService.decrypt(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("S7PLAIN");
        when(cryptoService.digest(anyString(), anyString(), any())).thenReturn("sig");

        InterfaceTestResult r = service.test(20L, request("GATEWAY", "prod", null), null);

        assertEquals(Long.valueOf(7L), r.getAppId());
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("有效授权")), "要说明为什么选了这个应用");
    }

    @Test
    @DisplayName("GATEWAY：授权已过期的不算数（不能只看 status=1）")
    void gatewaySkipsExpiredGrant() {
        when(apiInterfaceMapper.selectById(20L)).thenReturn(api(20L, 2L, "/test", "GET"));
        when(envConfigResolver.resolve(any(ApiInterface.class), anyString()))
                .thenReturn(effective("http://upstream:8080", 1000, 1000, 0));

        AppApiGrant expired = new AppApiGrant();
        expired.setId(2L);
        expired.setAppId(8L);
        expired.setStatus(1);
        expired.setValidTo(LocalDate.now().minusDays(1)); // 昨天就失效了
        when(appApiGrantMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(expired));
        when(appApiPermissionMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        // 兜底：任意启用中的应用
        when(appMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(app(9L, "兜底应用", "K9", "S9")));
        when(cryptoService.decrypt(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("S9PLAIN");
        when(cryptoService.digest(anyString(), anyString(), any())).thenReturn("sig");

        InterfaceTestResult r = service.test(20L, request("GATEWAY", "prod", null), null);

        assertEquals(Long.valueOf(9L), r.getAppId(), "过期授权不能被选中");
        assertTrue(r.getNotes().stream().anyMatch(n -> n.contains("403")),
                "兜底时要说清『预期返回 403』——那本身就是有价值的验证结果");
    }

    @Test
    @DisplayName("GATEWAY：一个应用都没有 → 抛出可读的 400，而不是 500")
    void gatewayWithoutAnyApp() {
        when(apiInterfaceMapper.selectById(20L)).thenReturn(api(20L, 2L, "/test", "GET"));
        when(envConfigResolver.resolve(any(ApiInterface.class), anyString()))
                .thenReturn(effective("http://upstream:8080", 1000, 1000, 0));
        when(appApiGrantMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(appApiPermissionMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(appMapper.selectList(any(QueryWrapper.class))).thenReturn(new ArrayList<>());

        InterfaceTestResult r = service.test(20L, request("GATEWAY", "prod", null), null);

        assertNotNull(r.getError());
        assertTrue(r.getError().contains("没有可用于试调的应用"));
    }

    // ===================== 边界 =====================

    @Test
    @DisplayName("未知 mode 一律按直连处理（前端传错不该 500）")
    void unknownModeFallsBackToDirect() {
        when(apiInterfaceMapper.selectById(20L)).thenReturn(api(20L, 2L, "/test", "GET"));
        when(envConfigResolver.resolve(any(ApiInterface.class), anyString()))
                .thenReturn(effective("http://127.0.0.1:1", 300, 300, 0));

        InterfaceTestResult r = service.test(20L, request("whatever", "prod", null), null);

        assertEquals("DIRECT", r.getMode());
        assertEquals("http://127.0.0.1:1/test", r.getTargetUrl());
    }

    @Test
    @DisplayName("接口不存在 → 404；apiId 为空 → 400")
    void validatesApiId() {
        when(apiInterfaceMapper.selectById(999L)).thenReturn(null);

        assertEquals(404, assertThrows(GatewayException.class,
                () -> service.test(999L, request("DIRECT", "prod", null), null)).getCode());
        assertEquals(400, assertThrows(GatewayException.class,
                () -> service.test(null, request("DIRECT", "prod", null), null)).getCode());
    }

    @Test
    @DisplayName("req 为 null 时按默认走（直连 + 默认环境 prod），不 NPE")
    void nullRequestUsesDefaults() {
        when(apiInterfaceMapper.selectById(20L)).thenReturn(api(20L, 2L, "/test", "GET"));
        when(envConfigResolver.resolve(any(ApiInterface.class), anyString()))
                .thenReturn(effective("http://127.0.0.1:1", 300, 300, 0));

        InterfaceTestResult r = service.test(20L, null, null);

        assertEquals("DIRECT", r.getMode());
        assertEquals("prod", r.getEnvCode());
    }

    // ===================== fixtures =====================

    private void set(String name, Object value) throws Exception {
        Field f = InterfaceTestServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private InterfaceTestRequest request(String mode, String env, Long appId) {
        InterfaceTestRequest req = new InterfaceTestRequest();
        req.setMode(mode);
        req.setEnvCode(env);
        req.setAppId(appId);
        return req;
    }

    private ApiInterface api(Long id, Long groupId, String path, String method) {
        ApiInterface a = new ApiInterface();
        a.setId(id);
        a.setGroupId(groupId);
        a.setInterfacePath(path);
        a.setRequestMethod(method);
        a.setBackendUrl("http://127.0.0.1:1");
        return a;
    }

    private App app(Long id, String name, String key, String secret) {
        App a = new App();
        a.setId(id);
        a.setAppName(name);
        a.setAppKey(key);
        a.setAppSecret(secret);
        a.setStatus(1);
        return a;
    }

    private EffectiveEnvConfig effective(String prefix, int connect, int read, int retry) {
        EffectiveEnvConfig e = new EffectiveEnvConfig();
        e.setEnvCode("prod");
        e.setUpstreamUrl(prefix);
        e.setConnectTimeout(connect);
        e.setReadTimeout(read);
        e.setRetryCount(retry);
        e.setMockEnabled(0);
        e.setConfigStatus(1);
        e.setSourceType(EffectiveEnvConfig.SOURCE_GROUP);
        e.setSourceGroupId(2L);
        e.setSourceGroupName("核心指标");
        e.setSourcePath("配网 / 核心指标");
        return e;
    }
}
