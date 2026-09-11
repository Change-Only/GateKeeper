package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.crypto.CryptoServiceImpl;
import com.gatekeeper.entity.App;
import com.gatekeeper.exception.GatewayException;
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
 * Nonce 重放拦截、签名错误、正常放行写上下文。</p>
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

    private final CryptoService cryptoService = new CryptoServiceImpl();
    private AppAuthHandler handler;

    /** 初始化 Handler，并让 Nonce 写入默认成功（不被重放拦截） */
    @BeforeEach
    void setUp() {
        handler = new AppAuthHandler(appMapper, cryptoService, securityDetectionService, redisTemplate);
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
