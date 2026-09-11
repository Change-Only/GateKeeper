package com.gatekeeper.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * 密钥工具 — 将配置中的原始 AES 密钥字符串规范化为 32 字节（AES-256）
 * 并转换为 CryptoService 所需的 Base64 编码格式
 *
 * <p>规则：不足 32 字节右侧补 '0'，超过 32 字节截断前 32 字节。</p>
 */
public class CryptoKeyUtil {

    /**
     * 将原始密钥字符串规范化为 Base64 编码的 32 字节 AES-256 密钥
     *
     * @param raw 配置中的原始密钥字符串
     * @return Base64 编码的 32 字节密钥
     */
    public static String toBase64Key(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("AES key must not be empty");
        }
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        byte[] key = Arrays.copyOf(bytes, 32); // 不足补 0，超出截断
        return Base64.getEncoder().encodeToString(key);
    }
}
