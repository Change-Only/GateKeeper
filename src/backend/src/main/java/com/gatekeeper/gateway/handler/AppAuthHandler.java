package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.entity.App;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.AppMapper;
import com.gatekeeper.security.SecurityDetectionService;
import com.gatekeeper.service.CredentialFacadeService;
import com.gatekeeper.util.CryptoKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Step 1: 应用状态/到期校验 + 签名验证
 *
 * <p>责任链第 1 环（必须先于 IP 白名单/封禁检查执行，二者依赖本环节解析出的 appId）：</p>
 * <ol>
 *   <li>校验 AppKey 是否存在且有效</li>
 *   <li>校验应用状态（1=启用，0=停用，2=已过期）</li>
 *   <li>校验到期时间是否已过</li>
 *   <li>强制校验签名 / 时间戳 / Nonce 请求头完整性（防伪造 / 防重放）</li>
 *   <li>校验时间戳时效（5 分钟窗口）</li>
 *   <li>Nonce 防重放校验</li>
 *   <li>校验请求签名：SM3(AppKey + AppSecret + Timestamp + Nonce)</li>
 * </ol>
 * <p>鉴权失败会调用安全检测服务记录，连续失败将触发自动 IP 封禁。</p>
 *
 * <p>T04-A 增量（fail-open，不影响既有鉴权主链路）：解析环境编码（请求头 X-Env → 默认环境），
 * 并「可选」核对该 app 在当前环境的活跃凭证（{@link CredentialFacadeService#getActiveCredential}）；
 * 若没有活跃凭证仅记录安全事件（WARN），<strong>绝不阻断</strong>链路——权威校验仍是 app 表的签名/状态。</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AppAuthHandler implements GatewayHandler {

    private final AppMapper appMapper;
    private final CryptoService cryptoService;
    private final SecurityDetectionService securityDetectionService;
    private final StringRedisTemplate redisTemplate;

    /** 凭证门面服务：按 appId+envCode 查询活跃凭证（可选依赖，fail-open，缺失不阻断） */
    @Autowired(required = false)
    private CredentialFacadeService credentialFacadeService;

    /** 数据库中 AppSecret 的 AES 加密密钥（配置文件注入，需与写入端一致） */
    @Value("${gatekeeper.crypto.aes-key}")
    private String aesDbKey;

    /** 默认环境编码（T04-A 引入，供 env-aware 凭证核对；与 PermissionHandler 一致） */
    @Value("${gatekeeper.env:prod}")
    private String defaultEnv;

    /**
     * 应用认证主流程：校验 AppKey、应用状态、到期时间、请求签名与时间戳
     *
     * @param ctx 网关上下文
     */
    @Override
    public void handle(GatewayContext ctx) {
        String appKey = ctx.getAppKey();
        if (appKey == null || appKey.isEmpty()) {
            // 记录鉴权失败（连续失败会触发自动封禁）
            securityDetectionService.recordAuthFailure(ctx.getClientIp(), null, "Missing AppKey");
            throw GatewayException.unauthorized("缺少 AppKey");
        }

        // 按 AppKey 查询应用
        App app = appMapper.selectOne(new QueryWrapper<App>().eq("app_key", appKey));
        if (app == null) {
            securityDetectionService.recordAuthFailure(ctx.getClientIp(), appKey, "Invalid AppKey");
            throw GatewayException.unauthorized("无效的 AppKey");
        }

        // 写入上下文，供后续 Handler（限流/权限/日志）使用
        ctx.setAppId(app.getId());
        ctx.setAppName(app.getAppName());

        // 应用状态校验（停用/过期均拒绝）
        if (app.getStatus() != 1) {
            String reason = app.getStatus() == 0 ? "应用已停用" : "应用已过期";
            ctx.setBlocked(true);
            ctx.setBlockReason(reason);
            throw GatewayException.forbidden(reason);
        }

        // 到期校验：到期时间早于当前时间则拒绝
        if (app.getExpireTime() != null && app.getExpireTime().isBefore(LocalDateTime.now())) {
            ctx.setBlocked(true);
            ctx.setBlockReason("App expired");
            throw GatewayException.forbidden("应用已过期，到期时间：" + app.getExpireTime());
        }

        // === 强制校验：签名、时间戳、Nonce 缺一不可（防伪造 / 防重放）===
        if (ctx.getSignature() == null || ctx.getSignature().isEmpty()) {
            securityDetectionService.recordAuthFailure(ctx.getClientIp(), appKey, "Missing signature");
            throw GatewayException.unauthorized("缺少请求签名 X-Signature");
        }
        if (ctx.getTimestamp() <= 0) {
            securityDetectionService.recordAuthFailure(ctx.getClientIp(), appKey, "Missing timestamp");
            throw GatewayException.unauthorized("缺少时间戳 X-Timestamp");
        }
        if (ctx.getNonce() == null || ctx.getNonce().isEmpty()) {
            securityDetectionService.recordAuthFailure(ctx.getClientIp(), appKey, "Missing nonce");
            throw GatewayException.unauthorized("缺少随机数 X-Nonce");
        }

        // 时间戳时效校验：与服务器时间偏差超过 5 分钟视为过期请求
        long diff = Math.abs(System.currentTimeMillis() - ctx.getTimestamp());
        if (diff > 5 * 60 * 1000) {
            securityDetectionService.recordAuthFailure(ctx.getClientIp(), appKey, "Expired timestamp");
            throw GatewayException.unauthorized("请求已过期，请检查时间戳");
        }

        // Nonce 防重放：同一 AppKey 的 Nonce 5 分钟内只允许使用一次
        checkNonce(ctx, appKey);

        // 签名验证（强制）
        verifySignature(ctx, app);

        // 异步触发安全检测（高频调用 / 异常时段），不阻塞主链路
        securityDetectionService.detectHighFrequency(app.getId(), app.getAppName());
        securityDetectionService.detectOffHours(app.getId(), app.getAppName(), ctx.getClientIp());

        // === T04-A：环境解析（X-Env 头优先，回退配置 gatekeeper.env，默认 prod）===
        String envCode = ctx.getEnvCode();
        if (envCode == null || envCode.isEmpty()) {
            String headerEnv = (ctx.getHttpRequest() != null)
                    ? ctx.getHttpRequest().getHeader("X-Env") : null;
            if (headerEnv != null && !headerEnv.isEmpty()) {
                envCode = headerEnv;
            } else {
                envCode = (defaultEnv != null && !defaultEnv.isEmpty()) ? defaultEnv : "prod";
            }
            ctx.setEnvCode(envCode);
        }

        // === T04-A：活跃凭证探测（可选，纯通知，绝不阻断主链路 fail-open）===
        if (app.getId() != null && credentialFacadeService != null) {
            try {
                if (credentialFacadeService.getActiveCredential(app.getId(), envCode) == null) {
                    securityDetectionService.recordAuthFailure(ctx.getClientIp(), appKey,
                            "No active credential for appId=" + app.getId() + ", env=" + envCode);
                }
            } catch (Exception e) {
                log.warn("Active credential check skipped (fail-open): {}", e.getMessage());
            }
        }

        ctx.setAuthSuccess(true);
        log.debug("App auth passed: appKey={}, appId={}, env={}", appKey, app.getId(), envCode);
    }

    /**
     * Nonce 防重放：使用 Redis SETNX 保证同一 Nonce 只放行一次，
     * 5 分钟窗口内重复提交直接拒绝
     *
     * <p>Redis 不可用时降级放行（fail-open）：防重放是签名/时间戳之外的增强防护，
     * 不应因 Redis 单点故障中断全部业务链路；降级会记录 ERROR 日志。</p>
     *
     * @param ctx    网关上下文
     * @param appKey 应用 Key
     */
    private void checkNonce(GatewayContext ctx, String appKey) {
        String nonce = ctx.getNonce();
        String nonceKey = "nonce:" + appKey + ":" + nonce;
        try {
            Boolean first = redisTemplate.opsForValue().setIfAbsent(nonceKey, "1", 5, TimeUnit.MINUTES);
            if (first == null || !first) {
                securityDetectionService.recordAuthFailure(ctx.getClientIp(), appKey, "Duplicate nonce");
                throw GatewayException.unauthorized("重复请求（Nonce 已使用）");
            }
        } catch (GatewayException e) {
            throw e; // 业务拒绝原样抛出
        } catch (Exception e) {
            // Redis 故障降级放行：防重放暂时失效，业务链路不中断
            log.error("Nonce check degraded (fail-open) for appKey={}: {}", appKey, e.getMessage());
        }
    }

    /**
     * 校验请求签名：SM3(AppKey + AppSecret明文 + Timestamp + Nonce) 是否与 X-Signature 一致。
     * AppSecret 以 AES 密文存储，校验前先解密；比较使用恒时算法防时序攻击。
     *
     * @param ctx 网关上下文（含签名与时间戳/随机数）
     * @param app 应用信息（含密文 AppSecret）
     */
    private void verifySignature(GatewayContext ctx, App app) {
        // 解密 AppSecret（与 AppServiceImpl 加密端使用相同密钥与算法）
        String plainSecret;
        try {
            plainSecret = cryptoService.decrypt("AES", app.getAppSecret(),
                    CryptoKeyUtil.toBase64Key(aesDbKey), null, "ECB", "PKCS5Padding");
        } catch (Exception e) {
            // 兼容未加密的历史数据：解密失败时按明文重试
            plainSecret = app.getAppSecret();
        }
        String signData = ctx.getAppKey() + plainSecret + ctx.getTimestamp() + ctx.getNonce();
        String expected = cryptoService.digest("SM3", signData, null);
        // 恒时比较，避免时序侧信道
        boolean ok = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                ctx.getSignature().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            securityDetectionService.recordAuthFailure(ctx.getClientIp(), ctx.getAppKey(), "Invalid signature");
            throw GatewayException.unauthorized("签名验证失败");
        }
    }
}
