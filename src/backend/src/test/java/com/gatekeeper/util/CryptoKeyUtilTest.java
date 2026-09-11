package com.gatekeeper.util;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AES 密钥规范化工具测试
 * 覆盖：短密钥补位、长密钥截断、空密钥拒绝
 */
class CryptoKeyUtilTest {

    /** 规范化结果解码后必须恰好 32 字节（AES-256） */
    @Test
    void shouldNormalizeTo32Bytes() {
        byte[] key = Base64.getDecoder().decode(CryptoKeyUtil.toBase64Key("short-key"));
        assertEquals(32, key.length, "短密钥应补位到 32 字节");
    }

    /** 恰好 32 字符的密钥应原样保留 */
    @Test
    void shouldKeepExactLengthKey() {
        String raw = "01234567890123456789012345678901";
        byte[] key = Base64.getDecoder().decode(CryptoKeyUtil.toBase64Key(raw));
        assertEquals(32, key.length);
        assertEquals(raw, new String(key));
    }

    /** 超长密钥应截断为前 32 字节 */
    @Test
    void shouldTruncateOverLengthKey() {
        String raw = "01234567890123456789012345678901EXTRA";
        byte[] key = Base64.getDecoder().decode(CryptoKeyUtil.toBase64Key(raw));
        assertEquals(32, key.length);
        assertEquals("01234567890123456789012345678901", new String(key));
    }

    /** 空密钥必须拒绝，避免生成弱密钥 */
    @Test
    void shouldRejectEmptyKey() {
        assertThrows(IllegalArgumentException.class, () -> CryptoKeyUtil.toBase64Key(""));
        assertThrows(IllegalArgumentException.class, () -> CryptoKeyUtil.toBase64Key(null));
    }
}
