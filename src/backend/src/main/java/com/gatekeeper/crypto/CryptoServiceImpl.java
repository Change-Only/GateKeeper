package com.gatekeeper.crypto;

import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.asymmetric.SM2;
import cn.hutool.crypto.digest.SM3;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 加解密服务实现 - 基于Hutool + Bouncy Castle
 *
 * <p>支持算法：</p>
 * <ul>
 *   <li>对称加密：SM4（国密）、AES（国际标准），支持 ECB/CBC 等模式与填充方式</li>
 *   <li>摘要：SM3（国密）、MD5、SHA256</li>
 *   <li>非对称：SM2 签名/验签</li>
 * </ul>
 * <p>密钥/IV/密文均使用 Base64 编码传输与存储。</p>
 */
@Slf4j
@Service
public class CryptoServiceImpl implements CryptoService {

    /**
     * 对称加密：按算法 + 模式 + 填充方式构造 Hutool SymmetricCrypto 并加密
     *
     * @param algorithm 算法: SM4 / AES
     * @param plainText 明文（为空时原样返回）
     * @param key Base64编码的密钥
     * @param iv Base64编码的IV向量（ECB 模式不需要）
     * @param mode 加密模式: ECB/CBC/CFB/OFB/CTR
     * @param padding 填充方式: PKCS5Padding/PKCS7Padding/NoPadding
     * @return Base64编码的密文
     */
    @Override
    public String encrypt(String algorithm, String plainText, String key, String iv, String mode, String padding) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        try {
            String alg = algorithm.toUpperCase();
            // 组装 JCE transformation：算法/模式/填充
            String transformation = alg + "/" + (mode != null ? mode : "ECB") + "/" + (padding != null ? padding : "PKCS5Padding");

            if ("SM4".equals(alg)) {
                cn.hutool.crypto.symmetric.SymmetricCrypto crypto = new cn.hutool.crypto.symmetric.SymmetricCrypto(
                        transformation,
                        cn.hutool.crypto.KeyUtil.generateKey("SM4", Base64.getDecoder().decode(key))
                );
                // 非 ECB 模式需要设置 IV 向量
                if (iv != null && !"ECB".equals(mode)) {
                    crypto.setIv(Base64.getDecoder().decode(iv));
                }
                return crypto.encryptBase64(plainText);
            } else if ("AES".equals(alg)) {
                cn.hutool.crypto.symmetric.SymmetricCrypto crypto = new cn.hutool.crypto.symmetric.SymmetricCrypto(
                        transformation,
                        cn.hutool.crypto.KeyUtil.generateKey("AES", Base64.getDecoder().decode(key))
                );
                // 非 ECB 模式需要设置 IV 向量
                if (iv != null && !"ECB".equals(mode)) {
                    crypto.setIv(Base64.getDecoder().decode(iv));
                }
                return crypto.encryptBase64(plainText);
            } else {
                throw new IllegalArgumentException("Unsupported encryption algorithm: " + algorithm);
            }
        } catch (Exception e) {
            log.error("Encrypt failed, algorithm={}: {}", algorithm, e.getMessage());
            throw new RuntimeException("加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 对称解密：与 encrypt 使用相同的算法/模式/密钥/IV 还原明文
     *
     * @param algorithm 算法: SM4 / AES
     * @param cipherText Base64编码的密文（为空时原样返回）
     * @param key Base64编码的密钥
     * @param iv Base64编码的IV向量（ECB 模式不需要）
     * @param mode 加密模式
     * @param padding 填充方式
     * @return 明文字符串
     */
    @Override
    public String decrypt(String algorithm, String cipherText, String key, String iv, String mode, String padding) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        try {
            String alg = algorithm.toUpperCase();
            // 组装 JCE transformation：算法/模式/填充
            String transformation = alg + "/" + (mode != null ? mode : "ECB") + "/" + (padding != null ? padding : "PKCS5Padding");

            if ("SM4".equals(alg)) {
                cn.hutool.crypto.symmetric.SymmetricCrypto crypto = new cn.hutool.crypto.symmetric.SymmetricCrypto(
                        transformation,
                        cn.hutool.crypto.KeyUtil.generateKey("SM4", Base64.getDecoder().decode(key))
                );
                if (iv != null && !"ECB".equals(mode)) {
                    crypto.setIv(Base64.getDecoder().decode(iv));
                }
                return crypto.decryptStr(cipherText);
            } else if ("AES".equals(alg)) {
                cn.hutool.crypto.symmetric.SymmetricCrypto crypto = new cn.hutool.crypto.symmetric.SymmetricCrypto(
                        transformation,
                        cn.hutool.crypto.KeyUtil.generateKey("AES", Base64.getDecoder().decode(key))
                );
                if (iv != null && !"ECB".equals(mode)) {
                    crypto.setIv(Base64.getDecoder().decode(iv));
                }
                return crypto.decryptStr(cipherText);
            } else {
                throw new IllegalArgumentException("Unsupported decryption algorithm: " + algorithm);
            }
        } catch (Exception e) {
            log.error("Decrypt failed, algorithm={}: {}", algorithm, e.getMessage());
            throw new RuntimeException("解密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算摘要（可选加盐：data + salt 拼接后计算）
     *
     * @param algorithm 算法: SM3 / MD5 / SHA256
     * @param input 输入字符串
     * @param salt 盐值（可选，为空时不加盐）
     * @return 十六进制摘要字符串
     */
    @Override
    public String digest(String algorithm, String input, String salt) {
        String data = input;
        if (salt != null && !salt.isEmpty()) {
            data = input + salt;
        }
        String alg = algorithm.toUpperCase();
        switch (alg) {
            case "SM3": {
                SM3 sm3 = new SM3();
                return sm3.digestHex(data);
            }
            case "MD5":
                return cn.hutool.crypto.SecureUtil.md5(data);
            case "SHA256":
                return cn.hutool.crypto.SecureUtil.sha256(data);
            default:
                throw new IllegalArgumentException("Unsupported digest algorithm: " + algorithm);
        }
    }

    /**
     * SM2 签名：使用私钥对数据签名，返回十六进制签名
     *
     * @param privateKey Base64编码的私钥
     * @param data 待签名数据
     * @return 十六进制签名
     */
    @Override
    public String sm2Sign(String privateKey, String data) {
        try {
            SM2 sm2 = SmUtil.sm2(privateKey, null);
            // 注意：Hutool 的 signHex 入参为十六进制数据，需先将明文转 Hex 再签名
            return sm2.signHex(cn.hutool.core.util.HexUtil.encodeHexStr(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("SM2 sign failed: {}", e.getMessage());
            throw new RuntimeException("SM2签名失败: " + e.getMessage(), e);
        }
    }

    /**
     * SM2 验签：使用公钥验证签名有效性
     *
     * @param publicKey Base64编码的公钥
     * @param data 原始数据
     * @param signature 十六进制签名
     * @return true=签名有效；false=签名无效（验签异常也返回 false）
     */
    @Override
    public boolean sm2Verify(String publicKey, String data, String signature) {
        try {
            SM2 sm2 = SmUtil.sm2(null, publicKey);
            // 与 sm2Sign 对应：验签同样传入十六进制数据与十六进制签名
            return sm2.verifyHex(cn.hutool.core.util.HexUtil.encodeHexStr(data.getBytes(StandardCharsets.UTF_8)),
                    signature);
        } catch (Exception e) {
            log.error("SM2 verify failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 生成SM2密钥对
     *
     * <p>注意：返回十六进制（Hex）格式密钥，与 {@link #sm2Sign} / {@link #sm2Verify}
     * 使用的 Hutool SM2 十六进制密钥格式保持一致
     * （若直接返回 Base64，签名时会因十六进制解析失败而抛异常）。</p>
     *
     * @return 数组 [公钥(Hex), 私钥(Hex)]
     */
    public static String[] generateSM2KeyPair() {
        SM2 sm2 = SmUtil.sm2();
        // Hutool 的 SmUtil.sm2(privateKey, publicKey) 按十六进制原始密钥解析：
        // 私钥取 D 值十六进制，公钥取未压缩公钥点（04 开头）
        String publicKeyHex = cn.hutool.core.util.HexUtil.encodeHexStr(sm2.getQ(false));
        String privateKeyHex = sm2.getDHex();
        return new String[]{publicKeyHex, privateKeyHex};
    }

    /**
     * 生成SM4密钥 (128bit)
     *
     * @return Base64 编码的 SM4 密钥
     */
    public static String generateSM4Key() {
        byte[] key = cn.hutool.crypto.KeyUtil.generateKey("SM4").getEncoded();
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * 生成AES密钥 (256bit)
     *
     * @return Base64 编码的 AES 密钥
     */
    public static String generateAESKey() {
        byte[] key = cn.hutool.crypto.KeyUtil.generateKey("AES", 256).getEncoded();
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * 生成IV向量 (16字节)
     *
     * @return Base64 编码的 16 字节随机 IV
     */
    public static String generateIV() {
        byte[] iv = cn.hutool.core.util.RandomUtil.randomBytes(16);
        return Base64.getEncoder().encodeToString(iv);
    }
}
