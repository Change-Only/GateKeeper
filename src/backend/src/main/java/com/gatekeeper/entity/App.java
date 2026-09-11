package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 应用表（app）— 应用接入方信息
 * 存储 AppKey、AppSecret、状态、描述、创建时间、到期时间，是网关鉴权与访问控制的核心实体
 */
@TableName("app")
public class App {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用名称 */
    private String appName;

    /** 应用唯一标识 AppKey（32位随机字符串，唯一） */
    private String appKey;

    /** 应用密钥 AppSecret（AES 加密后存储） */
    private String appSecret;

    /** 应用状态（1=启用 0=停用 2=已过期） */
    private Integer status;

    /** 应用描述 */
    private String description;

    /** 到期时间（NULL=永不过期） */
    private LocalDateTime expireTime;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
