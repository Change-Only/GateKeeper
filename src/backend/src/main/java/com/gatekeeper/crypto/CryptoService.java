package com.gatekeeper.crypto;

import java.util.Base64;

/**
 * 统一加解密服务接口
 *
 * <p>为网关提供统一的加解密能力，屏蔽底层算法库差异：</p>
 * <ul>
 *   <li>对称加密：SM4（国密）、AES（国际标准）</li>
 *   <li>摘要算法：SM3（国密）、MD5、SHA256</li>
 *   <li>非对称：SM2 签名/验签</li>
 * </ul>
 * <p>密钥与 IV 均以 Base64 编码字符串传递，密文以 Base64 编码返回。</p>
 */
public interface CryptoService {

    /**
     * 对称加密
     *
     * @param algorithm 算法: SM4 / AES
     * @param plainText 明文
     * @param key Base64编码的密钥
     * @param iv Base64编码的IV向量 (ECB模式可为null)
     * @param mode 加密模式: ECB/CBC/CFB/OFB/CTR
     * @param padding 填充方式: PKCS5Padding/PKCS7Padding/NoPadding
     * @return Base64编码的密文
     */
    String encrypt(String algorithm, String plainText, String key, String iv, String mode, String padding);

    /**
     * 对称解密
     *
     * @param algorithm 算法: SM4 / AES
     * @param cipherText Base64编码的密文
     * @param key Base64编码的密钥
     * @param iv Base64编码的IV向量 (ECB模式可为null)
     * @param mode 加密模式
     * @param padding 填充方式
     * @return 明文字符串
     */
    String decrypt(String algorithm, String cipherText, String key, String iv, String mode, String padding);

    /**
     * 计算摘要（可选加盐：data + salt 后计算）
     *
     * @param algorithm 算法: SM3 / MD5 / SHA256
     * @param input 输入字符串
     * @param salt 盐值(可选)
     * @return 十六进制摘要字符串
     */
    String digest(String algorithm, String input, String salt);

    /**
     * SM2 数字签名
     *
     * @param privateKey Base64编码的私钥
     * @param data 待签名数据
     * @return 十六进制签名
     */
    String sm2Sign(String privateKey, String data);

    /**
     * SM2 验签
     *
     * @param publicKey Base64编码的公钥
     * @param data 原始数据
     * @param signature 签名（十六进制）
     * @return 验签结果（true=签名有效）
     */
    boolean sm2Verify(String publicKey, String data, String signature);
}
