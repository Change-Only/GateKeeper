package com.gatekeeper.gateway.handler;

import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.security.SecurityDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Step 7: 异常入参检测（SQL 注入 / 路径穿越 / 超大 payload）
 *
 * <p>责任链第 7 环：在入参解密之后、请求转发之前，对请求体做安全检测。
 * 命中可疑特征即拦截并返回 400，同时异步记录安全事件。</p>
 * <ul>
 *   <li>超大 payload：请求体字节数超过阈值（默认 1MB）</li>
 *   <li>SQL 注入：' or '1'='1、union select、drop table 等特征</li>
 *   <li>路径穿越：../、..\、/etc/passwd 等特征</li>
 * </ul>
 */
@Slf4j
@Component
@Order(7)
@RequiredArgsConstructor
public class AbnormalParamCheckHandler implements GatewayHandler {

    private final SecurityDetectionService securityDetectionService;

    /** 最大请求体大小（字节），默认 1MB */
    @Value("${gatekeeper.security.max-payload-bytes:1048576}")
    private int maxPayloadBytes;

    /** 可疑入参特征（SQL 注入 / 路径穿越），命中即拦截 */
    private static final String[] INJECTION_PATTERNS = {
            "' or '1'='1", "\" or \"1\"=\"1", "' or 1=1", "' or '1'='1'",
            "union select", "union/**/select",
            "select * from", "insert into", "drop table", "delete from",
            "sleep(", "benchmark(",
            "../", "..\\", "..%2f", "..%5c", "/etc/passwd", "/etc/shadow"
    };

    /**
     * 执行异常入参检测
     *
     * @param ctx 网关上下文（需已由 EncryptionHandler 写入 decryptedBody）
     */
    @Override
    public void handle(GatewayContext ctx) {
        String rawBody = ctx.getRequestBody();

        // 1. 超大 payload 检查（按原始请求体字节数）
        if (rawBody != null && rawBody.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            String desc = "超大payload(" + rawBody.length() + "字符)";
            securityDetectionService.recordAbnormalParam(ctx.getClientIp(), ctx.getAppId(), ctx.getAppName(), desc);
            log.warn("Abnormal param blocked: appId={}, ip={}, reason={}", ctx.getAppId(), ctx.getClientIp(), desc);
            throw GatewayException.badRequest("请求体过大，已拦截");
        }

        // 2. SQL 注入 / 路径穿越检查（优先检查解密后的明文）
        String plain = ctx.getDecryptedBody() != null ? ctx.getDecryptedBody() : rawBody;
        if (plain != null && !plain.isEmpty()) {
            String hit = detectInjection(plain);
            if (hit != null) {
                securityDetectionService.recordAbnormalParam(ctx.getClientIp(), ctx.getAppId(), ctx.getAppName(), hit);
                log.warn("Abnormal param blocked: appId={}, ip={}, pattern={}", ctx.getAppId(), ctx.getClientIp(), hit);
                throw GatewayException.badRequest("检测到可疑入参，已拦截");
            }
        }
        log.debug("Abnormal param check passed for appId={}", ctx.getAppId());
    }

    /**
     * 检测文本中是否包含可疑注入特征
     *
     * @param text 待检测文本
     * @return 命中的特征串；未命中返回 null
     */
    private String detectInjection(String text) {
        String lower = text.toLowerCase();
        for (String pattern : INJECTION_PATTERNS) {
            if (lower.contains(pattern)) {
                return pattern;
            }
        }
        return null;
    }
}
