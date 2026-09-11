package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 接口环境配置 DTO — 接口环境配置管理页面入参/出参对象
 *
 * <p>T03b 接口生命周期子资源之一。对应 api_env_config 表，每个接口在不同环境（dev/test/pre/prod）
 * 下可有独立后端地址、超时、重试、Mock 配置。version 为 NULL 表示该环境所有版本通用。</p>
 *
 * <p>字段命名严格对齐原型 MOCK.apiEnvConfigs 字典：
 * <ul>
 *   <li>configStatus: 1=已配置, 2=未配置（由 upstreamUrl 是否填写推导）</li>
 *   <li>mockEnabled: 1=开启Mock, 0=关闭</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Data
public class ApiEnvConfigDto {

    /** 主键ID（创建时为空，更新/删除必填） */
    private Long id;

    /** 接口ID（原型 apiId） */
    @NotNull(message = "接口ID不能为空")
    private Long apiId;

    /** 环境编码（原型 envCode） */
    @NotBlank(message = "环境编码不能为空")
    private String envCode;

    /** 版本号，NULL=所有版本通用 */
    private String version;

    /** 后端服务地址（原型 upstreamUrl） */
    @NotBlank(message = "后端服务地址不能为空")
    private String upstreamUrl;

    /** 连接超时(ms)（原型 connectTimeout） */
    private Integer connectTimeout;

    /** 读取超时(ms)（原型 readTimeout） */
    private Integer readTimeout;

    /** 重试次数（原型 retryCount） */
    private Integer retryCount;

    /** 1=开启Mock, 0=关闭（原型 mockEnabled） */
    private Integer mockEnabled;

    /** 1=已配置, 2=未配置（原型 configStatus），由 upstreamUrl 推导 */
    private Integer configStatus;

    /** 创建时间（响应字段） */
    private LocalDateTime createdAt;

    /** 更新时间（响应字段） */
    private LocalDateTime updatedAt;
}
