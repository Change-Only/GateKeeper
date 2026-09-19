package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.config.SysConfigAccessor;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.entity.App;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.EnvResolver;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Step 1: 应用状态/到期校验 + 签名验证
 *
 * <p>责任链第 1 环（必须先于 IP 白名单/封禁检查执行，二者依赖本环节解析出的 appId）：</p>
 * <ol>
 *   <li>校验 AppKey 是否存在且有效</li>
 *   <li>校验应用状态（1=启用，0=停用，2=已过期）</li>
 *   <li>校验到期时间是否已过</li>
 *   <li>强制校验签名 / 时间戳 / Nonce 请求头完整性（防伪造 / 防重放）</li>
 *   <li>校验时间戳时效（窗口取自 {@code sys_config.sign.timestamp.tolerance}，默认 ±5 分钟）</li>
 *   <li>Nonce 防重放校验（TTL 取自 {@code sys_config.sign.nonce.ttl}，默认 600 秒）</li>
 *   <li>校验请求签名：SM3(AppKey + AppSecret + Timestamp + Nonce)</li>
 * </ol>
 * <p>鉴权失败会调用安全检测服务记录，连续失败将触发自动 IP 封禁。</p>
 *
 * <p><b>T19 配置接线</b>：第 5/6/7 步的参数不再是硬编码常量，而是通过
 * {@link SysConfigAccessor} 读取 {@code sys_config}（60s 本地缓存 + fail-open 回退默认值）。
 * 接线的意义是让「参数配置」页真正生效——此前这几项在页面上可改但代码从不读取，
 * 管理员会误以为改动了实际的防重放窗口／验签开关（实测确认无任何读取点）。</p>
 *
 * <p><b>应急开关 {@code gateway.auth.enabled}</b>：置 false 时跳过「防伪造/防重放」三件套
 * （头完整性、时间戳窗口、Nonce 唯一性、签名比对），但 <b>AppKey 存在性、应用状态、到期时间
 * 仍然强制校验</b> —— 该开关的语义是「应急放行调用方签名」，不是「关闭身份认证」。
 * 关闭期间每次状态翻转记一次 WARN，并由启动自检打 ERROR 留痕。</p>
 *
 * <p>T04-A 增量（fail-open，不影响既有鉴权主链路）：解析环境编码（统一走 {@link EnvResolver}），
 * 并「可选」核对该 app 在当前环境的活跃凭证（{@link CredentialFacadeService#getActiveCredential}）；
 * 若没有活跃凭证仅记录安全事件（WARN），<strong>绝不阻断</strong>链路——权威校验仍是 app 表的签名/状态。</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AppAuthHandler implements GatewayHandler {

    /** 默认时间戳容差（毫秒）—— sys_config 缺失 / 非法时回退，等于原来的硬编码 ±5 分钟 */
    static final int DEFAULT_TIMESTAMP_TOLERANCE_MS = 5 * 60 * 1000;
    /** 默认 Nonce TTL（秒）—— 与种子 sign.nonce.ttl=600 一致（≥ 2 倍时间戳容差） */
    static final int DEFAULT_NONCE_TTL_SECONDS = 600;
    /** 当前唯一合法的签名算法（客户端契约：SM3 国密摘要） */
    static final String SUPPORTED_SIGN_ALGORITHM = "SM3";

    private final AppMapper appMapper;
    private final CryptoService cryptoService;
    private final SecurityDetectionService securityDetectionService;
    private final StringRedisTemplate redisTemplate;
    /** 系统参数运行时读取器（T19 接线：时间戳容差 / Nonce TTL / 验签开关 / 签名算法） */
    private final SysConfigAccessor sysConfigAccessor;
    /** 环境码解析器（T19 收敛：X-Gk-Env > X-Env > gatekeeper.env > prod） */
    private final EnvResolver envResolver;

    /** 凭证门面服务：按 appId+envCode 查询活跃凭证（可选依赖，fail-open，缺失不阻断） */
    @Autowired(required = false)
    private CredentialFacadeService credentialFacadeService;

    /** 数据库中 AppSecret 的 AES 加密密钥（配置文件注入，需与写入端一致） */
    @Value("${gatekeeper.crypto.aes-key}")
    private String aesDbKey;

    /** 验签开关关闭只记一次 WARN，避免每请求刷屏（状态由配置决定，非持久标记） */
    private final AtomicBoolean authDisabledWarned = new AtomicBoolean(false);

    /** 非法签名算法只记一次 WARN */
    private final AtomicBoolean unsupportedAlgorithmWarned = new AtomicBoolean(false);

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

        // === T19：验签开关（sys_config.gateway.auth.enabled，默认开启）===
        // false 只跳过「防伪造/防重放」三件套；AppKey 存在性、应用状态、到期时间一律照旧校验
        // —— 该开关关闭的是「签名校验」，不是「身份认证」。
        boolean authEnabled = sysConfigAccessor.getBoolean(
                SysConfigAccessor.KEY_GATEWAY_AUTH_ENABLED, true);
        if (authEnabled) {
            checkAntiForgery(ctx, appKey, app);
        } else if (authDisabledWarned.compareAndSet(false, true)) {
            log.warn("⚠️ 网关签名校验已被配置关闭（sys_config.gateway.auth.enabled=false）："
                    + "防伪造/防重放全部失效，仅限应急临时使用，请尽快改回 true");
        }

        // 异步触发安全检测（高频调用 / 异常时段），不阻塞主链路
        securityDetectionService.detectHighFrequency(app.getId(), app.getAppName());
        securityDetectionService.detectOffHours(app.getId(), app.getAppName(), ctx.getClientIp());

        // === T19：环境解析 —— 统一走 EnvResolver（X-Gk-Env > X-Env > gatekeeper.env > prod）===
        String envCode = ctx.getEnvCode();
        if (envCode == null || envCode.isEmpty()) {
            envCode = envResolver.resolve(ctx);
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
     * 防伪造 / 防重放三件套：请求头完整性 → 时间戳窗口 → Nonce 唯一性 → 签名比对。
     *
     * <p>抽成独立方法是为了让 {@code gateway.auth.enabled=false} 的应急开关只影响这一段，
     * 而 AppKey / 应用状态 / 到期校验（身份认证部分）永远执行。</p>
     *
     * <p>三件套的参数全部来自 {@code sys_config}（T19 接线），缺失或非法时回退原硬编码常量，
     * 因此接线本身不改变默认行为。</p>
     *
     * @param ctx    网关上下文
     * @param appKey 应用 Key（已确认非空）
     * @param app    应用实体（已确认存在、启用且未过期）
     */
    private void checkAntiForgery(GatewayContext ctx, String appKey, App app) {
        // 1) 签名 / 时间戳 / Nonce 缺一不可
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

        // 2) 时间戳时效校验（窗口取自配置，默认 ±5 分钟）
        int toleranceMs = resolveTimestampToleranceMs();
        long diff = Math.abs(System.currentTimeMillis() - ctx.getTimestamp());
        if (diff > toleranceMs) {
            securityDetectionService.recordAuthFailure(ctx.getClientIp(), appKey, "Expired timestamp");
            throw GatewayException.unauthorized("请求已过期，请检查时间戳");
        }

        // 3) Nonce 防重放（TTL 取自配置）
        checkNonce(ctx, appKey);

        // 4) 签名比对（算法取自配置，当前仅 SM3 合法）
        verifySignature(ctx, app);
    }

    /**
     * 解析时间戳容差（毫秒）。
     *
     * @return 配置值；非正数 / 缺失 / 非法时回退 {@link #DEFAULT_TIMESTAMP_TOLERANCE_MS}
     */
    private int resolveTimestampToleranceMs() {
        int tolerance = sysConfigAccessor.getInt(
                SysConfigAccessor.KEY_SIGN_TIMESTAMP_TOLERANCE, DEFAULT_TIMESTAMP_TOLERANCE_MS);
        if (tolerance <= 0) {
            log.warn("sys_config[{}] 为非正数（{}），回退默认 {} ms",
                    SysConfigAccessor.KEY_SIGN_TIMESTAMP_TOLERANCE, tolerance, DEFAULT_TIMESTAMP_TOLERANCE_MS);
            return DEFAULT_TIMESTAMP_TOLERANCE_MS;
        }
        return tolerance;
    }

    /**
     * 解析 Nonce TTL（秒）。
     *
     * @return 配置值；非正数 / 缺失 / 非法时回退 {@link #DEFAULT_NONCE_TTL_SECONDS}
     */
    private int resolveNonceTtlSeconds() {
        int ttl = sysConfigAccessor.getInt(
                SysConfigAccessor.KEY_SIGN_NONCE_TTL, DEFAULT_NONCE_TTL_SECONDS);
        if (ttl <= 0) {
            log.warn("sys_config[{}] 为非正数（{}），回退默认 {} 秒",
                    SysConfigAccessor.KEY_SIGN_NONCE_TTL, ttl, DEFAULT_NONCE_TTL_SECONDS);
            return DEFAULT_NONCE_TTL_SECONDS;
        }
        return ttl;
    }

    /**
     * 解析签名算法。
     *
     * <p><b>刻意做成「只接受 SM3」而不是自由可配</b>：签名算法是跨端契约
     * （客户端 SDK 与 {@code docs/} 接入文档都按 SM3 实现，{@code InterfaceTestServiceImpl}
     * 的内置自测同样用 SM3），把它做成运行时可切换到别的算法，等于给线上留一个
     * 「改一行配置 → 全量验签失败」的开关。因此这里读取配置只是为了在配错时
     * 明确告警并回退，而不是真的允许切换。</p>
     *
     * @return 实际使用的算法（恒为 {@link #SUPPORTED_SIGN_ALGORITHM}）
     */
    private String resolveSignAlgorithm() {
        String configured = sysConfigAccessor.getString(
                SysConfigAccessor.KEY_SIGN_ALGORITHM, SUPPORTED_SIGN_ALGORITHM);
        if (SUPPORTED_SIGN_ALGORITHM.equalsIgnoreCase(configured)) {
            return SUPPORTED_SIGN_ALGORITHM;
        }
        if (unsupportedAlgorithmWarned.compareAndSet(false, true)) {
            log.warn("sys_config[{}] 配置为 {}，但当前实现仅支持 {}（客户端契约），已按 {} 处理",
                    SysConfigAccessor.KEY_SIGN_ALGORITHM, configured,
                    SUPPORTED_SIGN_ALGORITHM, SUPPORTED_SIGN_ALGORITHM);
        }
        return SUPPORTED_SIGN_ALGORITHM;
    }

    /**
     * Nonce 防重放：使用 Redis SETNX 保证同一 Nonce 只放行一次，
     * 有效窗口由 {@code sys_config.sign.nonce.ttl}（默认 600 秒）决定。
     *
     * <p>T19 修正：此前硬编码 5 分钟（300 秒），与种子 {@code sign.nonce.ttl=600}
     * 及其备注「应 ≥ 2 倍时间戳容差」不一致——时间戳窗口是 ±5 分钟，
     * 一条请求在被拒之前最多可被重放 5 分钟，因此「记住 Nonce」的时长必须 ≥ 5 分钟，
     * 300 秒恰好卡在边界上（临界点存在可重放的缝隙）。接线后按配置取 600 秒，
     * 与设计意图对齐；Redis 侧仅多存 5 分钟键，代价可忽略。</p>
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
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(nonceKey, "1", resolveNonceTtlSeconds(), TimeUnit.SECONDS);
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
        // T19：算法取自 sys_config.sign.algorithm（仅 SM3 合法，配错则告警并回退）
        String expected = cryptoService.digest(resolveSignAlgorithm(), signData, null);
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
