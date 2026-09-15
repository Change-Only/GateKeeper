package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.entity.ApiEncryptionConfig;
import com.gatekeeper.entity.AppEncryptionConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.EncryptionConfigResolver;
import com.gatekeeper.gateway.dto.EffectiveGroupEncryption;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.ApiEncryptionConfigMapper;
import com.gatekeeper.mapper.AppEncryptionConfigMapper;
import com.gatekeeper.service.SysEncryptionConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Step 6: 入参解密
 * Step 8: 响应加密
 * (两个功能合并在一个Handler中，通过方法区分)
 *
 * <p><b>加解密配置优先级（T15-1 起为三档）</b>：
 * <b>接口级 &gt; 分组级（沿分组树向上继承） &gt; 应用级</b> &gt; 无加密（明文传输）。</p>
 *
 * <p><b>T16-1 平台级总开关凌驾于上述三档之上</b>：{@code sys_encryption_config.enabled = 0}
 * 时直接短路返回（全平台强制明文），连接口级配置也不生效。这是刻意的
 * "总闸"语义 —— 用户口径为「仅总闸，不留平台密钥」，平台级不提供算法与密钥兜底。</p>
 *
 * <p>三档的「终止」语义各不相同，务必区分：
 * <ul>
 *   <li><b>接口级</b>：存在配置行即终止（保留老语义）—— 行内 {@code requestEncrypted=false}
 *       即表示「接口级显式明文」，不再向下回退；</li>
 *   <li><b>分组级</b>：沿 {@code api_interface.group_id} 的 parent_id 链上溯 ——
 *       命中 {@code ENABLED} 采用本层配置；命中 {@code DISABLED} <b>终止且不回退应用级</b>；
 *       {@code INHERIT} 继续上溯，整条链都没有则视为未配置；</li>
 *   <li><b>应用级</b>：最低优先级，兜底。</li>
 * </ul></p>
 *
 * <p>入参解密失败返回 400；响应加密失败降级返回明文，避免接口完全不可用。</p>
 */
@Slf4j
@Component
@Order(6)
@RequiredArgsConstructor
public class EncryptionHandler implements GatewayHandler {

    private final ApiEncryptionConfigMapper apiEncMapper;
    private final AppEncryptionConfigMapper appEncMapper;
    private final CryptoService cryptoService;
    /** T15-1：分组级加解密继承解析（唯一口径，与前端「生效预览」共用同一份逻辑） */
    private final EncryptionConfigResolver encryptionConfigResolver;
    /** T16-1：平台级加解密总开关（关闭 ⇒ 全局强制明文，短路整条解析链） */
    private final SysEncryptionConfigService sysEncryptionConfigService;

    /**
     * 责任链执行入口：加载加解密配置并解密入参
     *
     * @param ctx 网关上下文
     */
    @Override
    public void handle(GatewayContext ctx) {
        loadEncryptionConfig(ctx);
        decryptRequest(ctx);
    }

    /**
     * 加载加解密配置（三档优先级：接口级 &gt; 分组级 &gt; 应用级）
     *
     * <p>顺序：先查 {@code api_encryption_config}（接口级），命中即采用并终止；
     * 未命中再沿接口所属分组的父链解析分组级（{@link EncryptionConfigResolver}），
     * 命中 {@code DISABLED} 直接终止且不回退；
     * 两者都没有才回退 {@code app_encryption_config}（应用级）。</p>
     *
     * @param ctx 网关上下文（写入入参/返参的算法、模式、密钥、IV、填充方式）
     */
    private void loadEncryptionConfig(GatewayContext ctx) {
        // T16-1：平台级总开关 —— 关闭即**全局强制明文**（短路，忽略所有层级配置）。
        // isGloballyEnabled() 内部 fail-safe：读不到开关时返回 true（保持加密），
        // 绝不能因为一次 DB 抖动就把全平台打成明文。
        if (!sysEncryptionConfigService.isGloballyEnabled()) {
            log.info("平台加解密总开关已关闭，本次按明文处理: appId={}, ifaceId={}",
                    ctx.getAppId(), ctx.getInterfaceId());
            return;
        }

        // 接口级配置
        ApiEncryptionConfig apiConfig = apiEncMapper.selectOne(
                new QueryWrapper<ApiEncryptionConfig>().eq("interface_id", ctx.getInterfaceId())
        );

        if (apiConfig != null) {
            // 入参加密配置
            if (apiConfig.getRequestEncrypted()) {
                ctx.setRequestEncrypted(true);
                ctx.setRequestAlgorithm(apiConfig.getRequestAlgorithm());
                ctx.setRequestMode(apiConfig.getRequestMode());
                ctx.setRequestKey(apiConfig.getRequestKey());
                ctx.setRequestIv(apiConfig.getRequestIv());
                ctx.setRequestPadding(apiConfig.getRequestPadding());
                ctx.setEncryptionAlgorithm(apiConfig.getRequestAlgorithm());
            }
            // 返参加密配置
            if (apiConfig.getResponseEncrypted()) {
                ctx.setResponseEncrypted(true);
                ctx.setResponseAlgorithm(apiConfig.getResponseAlgorithm());
                ctx.setResponseMode(apiConfig.getResponseMode());
                ctx.setResponseKey(apiConfig.getResponseKey());
                ctx.setResponseIv(apiConfig.getResponseIv());
                ctx.setResponsePadding(apiConfig.getResponsePadding());
            }
            return;
        }

        // T15-1：分组级（沿分组树向上继承；支持「显式不需要加解密」）
        EffectiveGroupEncryption groupCfg =
                encryptionConfigResolver.resolveByInterface(ctx.getInterfaceId());
        if (groupCfg != null) {
            if (groupCfg.isDisabled()) {
                // 某层分组显式选了「不需要加解密」⇒ 明文传输，且**不**回退应用级：
                // 用户语义是「这条链路就是不需要加密」，若回退又变成加密，与预期相反。
                log.debug("Encryption explicitly disabled by group: appId={}, ifaceId={}, path={}",
                        ctx.getAppId(), ctx.getInterfaceId(), groupCfg.getSourcePath());
                return;
            }
            applyGroupConfig(ctx, groupCfg);
            return;
        }

        // 回退到应用级配置（接口级与分组链均未配置时）
        AppEncryptionConfig appConfig = appEncMapper.selectOne(
                new QueryWrapper<AppEncryptionConfig>().eq("app_id", ctx.getAppId())
        );
        if (appConfig != null) {
            if (appConfig.getAlgorithm() != null) {
                // 应用级配置同时作用于入参与返参
                ctx.setRequestEncrypted(true);
                ctx.setRequestAlgorithm(appConfig.getAlgorithm());
                ctx.setRequestMode(appConfig.getMode());
                ctx.setRequestKey(appConfig.getSecretKey());
                ctx.setRequestIv(appConfig.getIv());
                ctx.setRequestPadding(appConfig.getPadding());
                ctx.setEncryptionAlgorithm(appConfig.getAlgorithm());

                ctx.setResponseEncrypted(true);
                ctx.setResponseAlgorithm(appConfig.getAlgorithm());
                ctx.setResponseMode(appConfig.getMode());
                ctx.setResponseKey(appConfig.getSecretKey());
                ctx.setResponseIv(appConfig.getIv());
                ctx.setResponsePadding(appConfig.getPadding());
            }
        }
    }

    /**
     * 解密请求参数（未启用入参加密时原样透传）
     *
     * @param ctx 网关上下文（解密结果写入 decryptedBody）
     */
    private void decryptRequest(GatewayContext ctx) {
        if (!ctx.isRequestEncrypted() || ctx.getRequestBody() == null || ctx.getRequestBody().isEmpty()) {
            // 无加密配置或空请求体：直接透传明文
            ctx.setDecryptedBody(ctx.getRequestBody());
            return;
        }
        try {
            // 按配置的算法/模式/密钥/IV 解密请求体
            String decrypted = cryptoService.decrypt(
                    ctx.getRequestAlgorithm(),
                    ctx.getRequestBody(),
                    ctx.getRequestKey(),
                    ctx.getRequestIv(),
                    ctx.getRequestMode(),
                    ctx.getRequestPadding()
            );
            ctx.setDecryptedBody(decrypted);
            log.debug("Request decrypted: appId={}, algorithm={}", ctx.getAppId(), ctx.getRequestAlgorithm());
        } catch (Exception e) {
            // 解密失败（密钥不匹配/密文损坏）直接拒绝请求
            log.error("Request decrypt failed: {}", e.getMessage());
            throw GatewayException.badRequest("入参解密失败: " + e.getMessage());
        }
    }

    /**
     * 加密响应数据（未启用返参加密时原样返回）
     *
     * @param ctx 网关上下文（含后端响应明文）
     * @return 加密后的响应体（密文），加密失败时降级返回明文
     */
    public String encryptResponse(GatewayContext ctx) {
        if (!ctx.isResponseEncrypted() || ctx.getResponseBody() == null) {
            return ctx.getResponseBody();
        }
        try {
            String encrypted = cryptoService.encrypt(
                    ctx.getResponseAlgorithm(),
                    ctx.getResponseBody(),
                    ctx.getResponseKey(),
                    ctx.getResponseIv(),
                    ctx.getResponseMode(),
                    ctx.getResponsePadding()
            );
            ctx.setEncryptedResponseBody(encrypted);
            log.debug("Response encrypted: appId={}, algorithm={}", ctx.getAppId(), ctx.getResponseAlgorithm());
            return encrypted;
        } catch (Exception e) {
            log.error("Response encrypt failed: {}", e.getMessage());
            return ctx.getResponseBody(); // 加密失败返回明文，避免完全不可用
        }
    }

    /**
     * 把「分组级生效配置」写入网关上下文。
     *
     * <p>与接口级一致：入参、返参各自独立开关（不强制「要么都加要么都不加」）。</p>
     *
     * @param ctx 网关上下文
     * @param g   分组级生效配置（调用方已保证 {@code enabled=true}）
     */
    private void applyGroupConfig(GatewayContext ctx, EffectiveGroupEncryption g) {
        if (Boolean.TRUE.equals(g.getRequestEncrypted())) {
            ctx.setRequestEncrypted(true);
            ctx.setRequestAlgorithm(g.getRequestAlgorithm());
            ctx.setRequestMode(g.getRequestMode());
            ctx.setRequestKey(g.getRequestKey());
            ctx.setRequestIv(g.getRequestIv());
            ctx.setRequestPadding(g.getRequestPadding());
            ctx.setEncryptionAlgorithm(g.getRequestAlgorithm());
        }
        if (Boolean.TRUE.equals(g.getResponseEncrypted())) {
            ctx.setResponseEncrypted(true);
            ctx.setResponseAlgorithm(g.getResponseAlgorithm());
            ctx.setResponseMode(g.getResponseMode());
            ctx.setResponseKey(g.getResponseKey());
            ctx.setResponseIv(g.getResponseIv());
            ctx.setResponsePadding(g.getResponsePadding());
        }
        log.debug("Encryption from group chain: appId={}, ifaceId={}, group={}, path={}, req={}, resp={}",
                ctx.getAppId(), ctx.getInterfaceId(), g.getSourceGroupName(), g.getSourcePath(),
                ctx.isRequestEncrypted(), ctx.isResponseEncrypted());
    }
}
