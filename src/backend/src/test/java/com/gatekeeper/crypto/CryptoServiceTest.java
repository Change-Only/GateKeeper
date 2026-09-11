package com.gatekeeper.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加解密服务测试
 * 覆盖：SM4/AES 加解密往返、SM3/MD5/SHA256 摘要、SM2 签名验签、密钥生成
 */
class CryptoServiceTest {

    private final CryptoService cryptoService = new CryptoServiceImpl();

    /** SM4-CBC 加解密往返 */
    @Test
    void shouldRoundTripSm4() {
        String key = CryptoServiceImpl.generateSM4Key();
        String iv = CryptoServiceImpl.generateIV();
        String plain = "{\"orderId\":\"SO20260829001\",\"amount\":199.9}";

        String cipher = cryptoService.encrypt("SM4", plain, key, iv, "CBC", "PKCS5Padding");
        assertNotEquals(plain, cipher, "密文不应等于明文");

        String decrypted = cryptoService.decrypt("SM4", cipher, key, iv, "CBC", "PKCS5Padding");
        assertEquals(plain, decrypted, "SM4 解密应还原明文");
    }

    /** SM4 不同 IV 应产生不同密文（防密文比对泄露） */
    @Test
    void shouldProduceDifferentCipherWithDifferentIv() {
        String key = CryptoServiceImpl.generateSM4Key();
        String a = cryptoService.encrypt("SM4", "same-text", key, CryptoServiceImpl.generateIV(), "CBC", "PKCS5Padding");
        String b = cryptoService.encrypt("SM4", "same-text", key, CryptoServiceImpl.generateIV(), "CBC", "PKCS5Padding");
        assertNotEquals(a, b);
    }

    /** AES-CBC 加解密往返 */
    @Test
    void shouldRoundTripAes() {
        String key = CryptoServiceImpl.generateAESKey();
        String iv = CryptoServiceImpl.generateIV();
        String plain = "GateKeeper 网关敏感数据";

        String cipher = cryptoService.encrypt("AES", plain, key, iv, "CBC", "PKCS5Padding");
        assertEquals(plain, cryptoService.decrypt("AES", cipher, key, iv, "CBC", "PKCS5Padding"));
    }

    /** 空明文应原样返回，不抛异常 */
    @Test
    void shouldPassThroughEmptyText() {
        String key = CryptoServiceImpl.generateSM4Key();
        assertEquals("", cryptoService.encrypt("SM4", "", key, null, "ECB", "PKCS5Padding"));
        assertEquals("", cryptoService.decrypt("SM4", "", key, null, "ECB", "PKCS5Padding"));
    }

    /** 摘要算法：SM3/MD5/SHA256 长度与稳定性 */
    @Test
    void shouldDigestCorrectly() {
        String sm3 = cryptoService.digest("SM3", "abc", null);
        assertEquals(64, sm3.length(), "SM3 应为 64 位十六进制");
        assertEquals(sm3, cryptoService.digest("SM3", "abc", null), "摘要应稳定");
        assertNotEquals(sm3, cryptoService.digest("SM3", "abc", "salt"), "加盐后摘要应不同");

        assertEquals(32, cryptoService.digest("MD5", "abc", null).length());
        assertEquals(64, cryptoService.digest("SHA256", "abc", null).length());
    }

    /** SM2 签名与验签：正确签名通过、篡改数据不通过 */
    @Test
    void shouldSignAndVerifyWithSm2() {
        String[] keyPair = CryptoServiceImpl.generateSM2KeyPair();
        String publicKey = keyPair[0];
        String privateKey = keyPair[1];
        assertNotNull(publicKey);
        assertNotNull(privateKey);

        String data = "appKey=xxx&timestamp=1234567890&nonce=abc123";
        String sign = cryptoService.sm2Sign(privateKey, data);
        assertTrue(cryptoService.sm2Verify(publicKey, data, sign), "正确签名应验签通过");
        assertFalse(cryptoService.sm2Verify(publicKey, data + "tampered", sign), "篡改数据应验签失败");
    }

    /** 密钥生成应每次不同且非空 */
    @Test
    void shouldGenerateRandomKeys() {
        assertNotEquals(CryptoServiceImpl.generateSM4Key(), CryptoServiceImpl.generateSM4Key());
        assertNotEquals(CryptoServiceImpl.generateIV(), CryptoServiceImpl.generateIV());
        assertTrue(CryptoServiceImpl.generateSM4Key().length() > 0);
        assertTrue(CryptoServiceImpl.generateAESKey().length() > 0);
    }
}
