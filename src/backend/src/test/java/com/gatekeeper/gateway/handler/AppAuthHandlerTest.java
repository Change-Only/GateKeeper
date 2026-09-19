package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.config.SysConfigAccessor;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.crypto.CryptoServiceImpl;
import com.gatekeeper.entity.App;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.EnvResolver;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.AppMapper;
import com.gatekeeper.security.SecurityDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用认证 Handler 测试（网关鉴权核心链路）
 *
 * <p>覆盖：AppKey 缺失/无效、应用停用/过期、签名/时间戳/Nonce 强制校验、
 * Nonce 重放拦截、签名错误、正常放行写上下文，以及 T19 新增的配置接线行为
 * （{@code gateway.auth.enabled} 应急开关、{@code sign.timestamp.tolerance}、
 * {@code sign.nonce.ttl}、{@code sign.algorithm}）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppAuthHandlerTest {

    private static final String APP_KEY = "ak_test_0001";
    private static final String APP_SECRET = "sk_test_secret_0123456789012345678901234567890";
    private static final String CLIENT_IP = "203.0.113.10";

    @Mock
    private AppMapper appMapper;
    @Mock
    private SecurityDetectionService securityDetectionService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SysConfigAccessor sysConfigAccessor;
    @Mock
    private javax.servlet.http.HttpServletRequest servletRequest;

    private final CryptoService cryptoService = new CryptoServiceImpl();
    private final EnvResolver envResolver = new EnvResolver();
    private AppAuthHandler handler;

    /**
     * 初始化 Handler，并让 Nonce 写入默认成功（不被重放拦截）。
     *
     * <p>T19：{@link SysConfigAccessor} 用 mock，默认行为是「配置表里没有这个键」
     * ——一律回退调用方传入的默认值，等价于接线前的硬编码行为。</p>
     */
    @BeforeEach
    void setUp() {
        lenient().when(sysConfigAccessor.getBoolean(anyString(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(sysConfigAccessor.getInt(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(sysConfigAccessor.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        handler = new AppAuthHandler(appMapper, cryptoService, securityDetectionService,
                redisTemplate, sysConfigAccessor, envResolver);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    /** 缺少 AppKey 应直接 401，并记录鉴权失败 */
    @Test
    void shouldRejectWhenAppKeyMissing() {
        GatewayContext ctx = baseContext(null, "sig", now(), "n1");

        GatewayException ex = assertThrows(GatewayException.class, () -> handler.handle(ctx));
        assertEquals(401, ex.getCode());
        verify(securityDetectionService).recordAuthFailure(eq(CLIENT_IP), eq(null), anyString());
    }

    /** AppKey 不存在应 401 */
    @Test
    void shouldRejectWhenAppKeyInvalid() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        GatewayContext ctx = baseContext("unknown-key", "sig", now(), "n1");

        GatewayException ex = assertThrows(GatewayException.class, () -> handler.handle(ctx));
        assertEquals(401, ex.getCode());
    }

    /** 已停用的应用应 403 */
    @Test
    void shouldRejectWhenAppDisabled() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(0, null));
        GatewayContext ctx = baseContext(APP_KEY, "sig", now(), "n1");

        GatewayException ex = assertThrows(GatewayException.class, () -> handler.handle(ctx));
        assertEquals(403, ex.getCode());
    }

    /** 已过期的应用应 403 */
    @Test
    void shouldRejectWhenAppExpired() {
        when(appMapper.selectOne(any(QueryWrapper.class)))
                .thenReturn(buildApp(1, LocalDateTime.now().minusDays(1)));
        GatewayContext ctx = baseContext(APP_KEY, "sig", now(), "n1");

        GatewayException ex = assertThrows(GatewayException.class, () -> handler.handle(ctx));
        assertEquals(403, ex.getCode());
    }

    /** 签名/时间戳/Nonce 三者缺一不可（防伪造、防重放强制校验） */
    @Test
    void shouldRejectWhenSignatureOrTimestampOrNonceMissing() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(1, null));

        assertEquals(401, assertThrows(GatewayException.class,
                () -> handler.handle(baseContext(APP_KEY, null, now(), "n1"))).getCode(), "缺少签名应 401");
        assertEquals(401, assertThrows(GatewayException.class,
                () -> handler.handle(baseContext(APP_KEY, "sig", 0L, "n1"))).getCode(), "缺少时间戳应 401");
        assertEquals(401, assertThrows(GatewayException.class,
                () -> handler.handle(baseContext(APP_KEY, "sig", now(), null))).getCode(), "缺少 Nonce 应 401");
    }

    /** 超出 5 分钟窗口的时间戳应 401 */
    @Test
    void shouldRejectExpiredTimestamp() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(1, null));
        long stale = System.currentTimeMillis() - 6 * 60 * 1000;

        GatewayException ex = assertThrows(GatewayException.class,
                () -> handler.handle(baseContext(APP_KEY, "sig", stale, "n1")));
        assertEquals(401, ex.getCode());
    }

    /** 重复 Nonce（Redis SETNX 失败）应被拦截 */
    @Test
    void shouldRejectDuplicateNonce() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(1, null));
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        GatewayContext ctx = baseContext(APP_KEY, sign(APP_KEY, APP_SECRET, now(), "dup"), now(), "dup");
        GatewayException ex = assertThrows(GatewayException.class, () -> handler.handle(ctx));
        assertEquals(401, ex.getCode());
    }

    /** 签名不匹配应 401 */
    @Test
    void shouldRejectWrongSignature() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(1, null));

        GatewayException ex = assertThrows(GatewayException.class,
                () -> handler.handle(baseContext(APP_KEY, "wrong-signature", now(), "n1")));
        assertEquals(401, ex.getCode());
    }

    /** 合法请求应放行，并写入 appId/appName 供后续 Handler 使用 */
    @Test
    void shouldPassAndPopulateContext() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(1, null));
        long ts = now();
        GatewayContext ctx = baseContext(APP_KEY, sign(APP_KEY, APP_SECRET, ts, "ok-nonce"), ts, "ok-nonce");

        handler.handle(ctx);

        assertEquals(1L, ctx.getAppId(), "应写入应用 ID");
        assertEquals("测试应用", ctx.getAppName());
        assertEquals(true, ctx.isAuthSuccess(), "认证应标记为成功");
    }

    // ---------- T19：配置接线行为 ----------

    /** gateway.auth.enabled=false 时跳过防伪造/防重放，但应用状态仍必须有效 */
    @Test
    void shouldSkipAntiForgeryWhenSwitchDisabledButStillEnforceAppStatus() {
        when(sysConfigAccessor.getBoolean(eq(SysConfigAccessor.KEY_GATEWAY_AUTH_ENABLED), anyBoolean()))
                .thenReturn(false);
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(1, null));

        // 无签名/时间戳/Nonce 也应放行（应急开关语义）
        GatewayContext ctx = baseContext(APP_KEY, null, 0L, null);
        handler.handle(ctx);
        assertEquals(true, ctx.isAuthSuccess(), "验签开关关闭时应放行");

        // 但应用停用仍然 403 —— 关闭的是验签，不是身份认证
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(0, null));
        assertEquals(403, assertThrows(GatewayException.class,
                () -> handler.handle(baseContext(APP_KEY, null, 0L, null))).getCode(),
                "验签开关关闭不得放过已停用应用");
    }

    /** 时间戳容差取自 sys_config.sign.timestamp.tolerance */
    @Test
    void shouldApplyConfiguredTimestampTolerance() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(1, null));
        when(sysConfigAccessor.getInt(eq(SysConfigAccessor.KEY_SIGN_TIMESTAMP_TOLERANCE), anyInt()))
                .thenReturn(1000);

        long stale = System.currentTimeMillis() - 5_000;
        GatewayException ex = assertThrows(GatewayException.class,
                () -> handler.handle(baseContext(APP_KEY, "sig", stale, "n1")));
        assertEquals(401, ex.getCode(), "容差收敛到 1s 后，5s 前的时间戳应被拒绝");
    }

    /** Nonce TTL 取自 sys_config.sign.nonce.ttl（单位：秒） */
    @Test
    void shouldApplyConfiguredNonceTtl() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(1, null));
        when(sysConfigAccessor.getInt(eq(SysConfigAccessor.KEY_SIGN_NONCE_TTL), anyInt())).thenReturn(120);
        long ts = now();

        handler.handle(baseContext(APP_KEY, sign(APP_KEY, APP_SECRET, ts, "n-ttl"), ts, "n-ttl"));

        verify(valueOps).setIfAbsent(anyString(), eq("1"), eq(120L), eq(TimeUnit.SECONDS));
    }

    /** sign.algorithm 配成不支持的算法时应回退 SM3（而不是把线上验签整体打挂） */
    @Test
    void shouldFallbackToSm3WhenAlgorithmUnsupported() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(1, null));
        when(sysConfigAccessor.getString(eq(SysConfigAccessor.KEY_SIGN_ALGORITHM), anyString()))
                .thenReturn("HmacSHA256");
        long ts = now();

        GatewayContext ctx = baseContext(APP_KEY, sign(APP_KEY, APP_SECRET, ts, "n-alg"), ts, "n-alg");
        handler.handle(ctx);

        assertEquals(true, ctx.isAuthSuccess(), "客户端按 SM3 签名时，配错算法不应导致验签失败");
    }

    /** 环境解析统一走 EnvResolver：X-Gk-Env 应被优先采纳（修复前该头永不生效） */
    @Test
    void shouldResolveEnvFromPreferredHeader() {
        when(appMapper.selectOne(any(QueryWrapper.class))).thenReturn(buildApp(1, null));
        when(servletRequest.getHeader(EnvResolver.HEADER_ENV_PREFERRED)).thenReturn("gray");
        long ts = now();

        GatewayContext ctx = baseContext(APP_KEY, sign(APP_KEY, APP_SECRET, ts, "n-env"), ts, "n-env");
        ctx.setHttpRequest(servletRequest);
        handler.handle(ctx);

        assertEquals("gray", ctx.getEnvCode(), "X-Gk-Env 应优先于 X-Env 与默认环境");
    }

    // ---------- 辅助方法 ----------

    private App buildApp(Integer status, LocalDateTime expireTime) {
        App app = new App();
        app.setId(1L);
        app.setAppKey(APP_KEY);
        app.setAppSecret(APP_SECRET);
        app.setAppName("测试应用");
        app.setStatus(status);
        app.setExpireTime(expireTime);
        return app;
    }

    private GatewayContext baseContext(String appKey, String signature, long timestamp, String nonce) {
        GatewayContext ctx = new GatewayContext();
        ctx.setAppKey(appKey);
        ctx.setSignature(signature);
        ctx.setTimestamp(timestamp);
        ctx.setNonce(nonce);
        ctx.setClientIp(CLIENT_IP);
        return ctx;
    }

    private String sign(String appKey, String secret, long ts, String nonce) {
        return cryptoService.digest("SM3", appKey + secret + ts + nonce, null);
    }

    private long now() {
        return System.currentTimeMillis();
    }
}
