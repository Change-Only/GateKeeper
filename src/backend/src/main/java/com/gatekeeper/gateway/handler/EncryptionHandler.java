package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.entity.ApiEncryptionConfig;
import com.gatekeeper.entity.AppEncryptionConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.ApiEncryptionConfigMapper;
import com.gatekeeper.mapper.AppEncryptionConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Step 6: 入参解密
 * Step 8: 响应加密
 * (两个功能合并在一个Handler中，通过方法区分)
 *
 * <p>加解密配置优先级：接口级配置 &gt; 应用级配置 &gt; 无加密（明文传输）。
 * 入参解密失败返回 400；响应加密失败降级返回明文，避免接口完全不可用。</p>
 */
@Slf4j
@Component
@Order(6)
@RequiredArgsConstructor
public class EncryptionHandler implements GatewayHandler {

    private final ApiEncryptionConfigMapper apiEncMapper;
    private final AppEncryptionConfigMapper appEncMapper;
    private final CryptoService cryptoService;

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
     * 加载加解密配置（接口级优先，回退应用级）
     *
     * <p>先查 api_encryption_config（接口级），命中则直接采用；
     * 未命中再查 app_encryption_config（应用级）。</p>
     *
     * @param ctx 网关上下文（写入入参/返参的算法、模式、密钥、IV、填充方式）
     */
    private void loadEncryptionConfig(GatewayContext ctx) {
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

        // 回退到应用级配置（接口未配置时）
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
}
