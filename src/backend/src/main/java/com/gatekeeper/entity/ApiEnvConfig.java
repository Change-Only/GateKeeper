package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口环境配置表（api_env_config）
 *
 * <p>version 为 NULL 表示该环境所有版本通用；灰度版本可配独立上游（架构 D2）。
 * config_status: 1=已配置, 2=未配置。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("api_env_config")
public class ApiEnvConfig {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口ID（原型 apiId） */
    private Long apiId;

    /** 环境编码（原型 envCode） */
    private String envCode;

    /** 版本号，NULL=所有版本通用 */
    private String version;

    /** 后端服务地址（原型 upstreamUrl） */
    private String upstreamUrl;

    /** 连接超时(ms)（原型 connectTimeout） */
    private Integer connectTimeout;

    /** 读取超时(ms)（原型 readTimeout） */
    private Integer readTimeout;

    /** 重试次数（原型 retryCount） */
    private Integer retryCount;

    /** 1=开启Mock, 0=关闭（原型 mockEnabled） */
    private Integer mockEnabled;

    /** 1=已配置, 2=未配置（原型 configStatus） */
    private Integer configStatus;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}