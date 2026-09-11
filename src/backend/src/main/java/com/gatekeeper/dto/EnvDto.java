package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 环境 DTO — 环境与网关页面入参/出参对象
 *
 * <p>T03a 环境主数据的对外传输对象。{@code envCode} 一旦创建不可修改（架构 D1），
 * service 层在 update 时拒绝非 null 改动。</p>
 *
 * <p>字段命名严格对齐原型枚举字典 §十八（envs）。{@code https} 字段是 UI 展示推导字段
 * —— 由 {@code gatewayUrl} 协议头自动推导得出，前端展示「http/https」时使用。</p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@Data
public class EnvDto {

    /** 主键ID（创建时为空，更新/删除必填） */
    private Long id;

    /** 环境编码：仅允许 dev/test/pre/prod，创建后不可修改 */
    @NotBlank(message = "环境编码不能为空")
    @Pattern(regexp = "^(dev|test|pre|prod)$", message = "环境编码仅支持 dev/test/pre/prod")
    private String envCode;

    /** 环境名称（中文展示） */
    @NotBlank(message = "环境名称不能为空")
    @Size(max = 64, message = "环境名称长度不能超过64")
    private String envName;

    /** 网关入口地址，如 https://api-pre.example.com */
    @NotBlank(message = "网关地址不能为空")
    @Size(max = 256, message = "网关地址长度不能超过256")
    private String gatewayUrl;

    /** 是否 HTTPS：0=http, 1=https；由 gatewayUrl 协议头推导（响应字段） */
    private Integer https;

    /** 排序（升序展示） */
    private Integer sortOrder;

    /** 状态：0=停用, 1=启用, 2=已废弃 */
    private Integer status;

    /** 创建时间（响应字段） */
    private LocalDateTime createdAt;

    /** 更新时间（响应字段） */
    private LocalDateTime updatedAt;
}
