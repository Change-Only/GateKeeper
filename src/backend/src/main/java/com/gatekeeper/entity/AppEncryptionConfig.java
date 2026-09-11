package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 应用加解密配置表（app_encryption_config）— 应用级密钥与加解密算法配置
 * 与 app 一对一，存储对称/非对称密钥、加密模式、填充方式与签名算法，密钥均加密落库
 */
@TableName("app_encryption_config")
public class AppEncryptionConfig {
    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用ID（关联 app.id，唯一） */
    private Long appId;

    /** 加密算法（SM2/SM4/AES） */
    private String algorithm;

    /** 非对称公钥（SM2 公钥） */
    private String publicKey;

    /** 非对称私钥（SM2 私钥，加密存储） */
    private String privateKey;

    /** 对称密钥（Base64，加密存储） */
    private String secretKey;

    /** 初始向量 IV（Base64） */
    private String iv;

    /** 加密模式（ECB/CBC/CFB/OFB/CTR） */
    private String mode;

    /** 填充方式 */
    private String padding;

    /** 签名算法（SM3/SHA256） */
    private String signAlgorithm;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
