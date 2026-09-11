package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 接口加解密配置表（api_encryption_config）— 接口级请求/响应加解密配置
 * 与 api_interface 一对一，分别配置入参与返参的算法、模式、密钥与填充方式
 */
@TableName("api_encryption_config")
public class ApiEncryptionConfig {
    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口ID（关联 api_interface.id） */
    private Long interfaceId;

    /** 入参是否加密 */
    private Boolean requestEncrypted;

    /** 入参加密算法（SM4/AES） */
    private String requestAlgorithm;

    /** 入参加密模式（ECB/CBC/CFB/OFB/CTR） */
    private String requestMode;

    /** 入参密钥（Base64，库中 AES 加密） */
    private String requestKey;

    /** 入参 IV 向量（Base64） */
    private String requestIv;

    /** 入参填充方式 */
    private String requestPadding;

    /** 返参是否加密 */
    private Boolean responseEncrypted;

    /** 返参加密算法 */
    private String responseAlgorithm;

    /** 返参加密模式 */
    private String responseMode;

    /** 返参密钥 */
    private String responseKey;

    /** 返参 IV 向量 */
    private String responseIv;

    /** 返参填充方式 */
    private String responsePadding;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
