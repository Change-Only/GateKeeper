package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 接口分组环境配置 DTO — 分组环境配置页面入参/出参对象
 *
 * <p>T13：环境配置从接口侧下沉到接口分组侧（"维护的地方比较明确"），
 * 分组树向上继承。字段沿用 api_env_config 的口径，额外增加 Mock 响应体配置
 * （mockStatus / mockResponse）—— 因为本项目 Mock 此前"只存不用"，
 * 现在开启 Mock 后网关要**短路返回一段可配置的报文**。</p>
 *
 * <ul>
 *   <li>configStatus: 1=已配置（地址已填但未验证）, 2=已验证（连通性测试通过）</li>
 *   <li>mockEnabled: 1=开启Mock（网关短路不转发）, 0=关闭</li>
 * </ul>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@Data
public class ApiGroupEnvConfigDto {

    /** 主键ID（创建时为空，更新/删除必填） */
    private Long id;

    /** 接口分组ID */
    @NotNull(message = "分组ID不能为空")
    private Long groupId;

    /** 环境编码 */
    @NotBlank(message = "环境编码不能为空")
    private String envCode;

    /** 服务前缀（不含 URI） */
    @NotBlank(message = "服务前缀不能为空")
    private String upstreamUrl;

    /** 连接超时(ms) */
    private Integer connectTimeout;

    /** 读取超时(ms) */
    private Integer readTimeout;

    /** 重试次数 */
    private Integer retryCount;

    /** 1=开启Mock, 0=关闭 */
    private Integer mockEnabled;

    /** Mock 返回的 HTTP 状态码（默认 200） */
    private Integer mockStatus;

    /** Mock 返回体（留空 = 返回默认提示 JSON） */
    private String mockResponse;

    /** 1=已配置, 2=已验证；由 upstreamUrl 与连通性测试推导 */
    private Integer configStatus;

    /** 创建时间（响应字段） */
    private LocalDateTime createdAt;

    /** 更新时间（响应字段） */
    private LocalDateTime updatedAt;
}
