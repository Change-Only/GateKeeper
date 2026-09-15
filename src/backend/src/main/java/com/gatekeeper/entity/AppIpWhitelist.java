package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 应用IP白名单表（app_ip_whitelist）— 应用的来源IP访问控制
 * 存储应用允许访问的IP或CIDR网段，来源IP不在白名单内的请求将被网关拦截
 *
 * <p><b>T15-4 补齐：`env_code` 与 `status`</b>。
 * 这两列**线上表里早已存在**（建表时带了默认值 'prod' / 1），
 * 只是实体一直没映射，导致两个实际问题：
 * <ol>
 *   <li>{@code status} 在网关侧完全没被读取 ⇒ **停用(status=0)的条目依然在拦人**，
 *       用户无法通过"停用"临时放行某段 IP，只能删掉再加回来（且很容易忘掉原值）；</li>
 *   <li>{@code env_code} 无处可配，页面看不到环境维度。</li>
 * </ol>
 * 因此本类补上映射，并由 {@code IpWhitelistHandler} 按 {@code status=1} 过滤。</p>
 *
 * <p><b>{@code env_code} 的语义（勿按"按环境隔离"去用）</b>：它是**归类标注**维度，
 * 便于按环境分组查看，**不参与网关校验**。白名单是"要么放行要么拦死"的强语义，
 * 若按 env 过滤，会出现在 A 环境配的行把 B 环境的正常调用拦掉的误伤；
 * 误拦的代价远高于误放，故校验只看 {@code status}。</p>
 */
@TableName("app_ip_whitelist")
public class AppIpWhitelist {

    /** 状态：启用（参与网关校验） */
    public static final int STATUS_ENABLED = 1;

    /** 状态：停用（不参与网关校验，便于临时摘除而不丢记录） */
    public static final int STATUS_DISABLED = 0;

    /** 默认环境码（与 DDL 的 DEFAULT 'prod' 保持一致，避免实体值与库默认值两套口径） */
    public static final String DEFAULT_ENV_CODE = "prod";

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用ID（关联 app.id） */
    private Long appId;

    /** 允许访问的IP或CIDR网段（如 192.168.1.0/24） */
    private String ipCidr;

    /** 备注 */
    private String remark;

    /**
     * 环境编码（归类标注用，**不参与网关校验**；如 prod / pre / gray）
     *
     * <p>为空时按 {@link #DEFAULT_ENV_CODE} 处理。</p>
     */
    private String envCode;

    /**
     * 状态：1=启用（参与校验），0=停用（不参与校验）
     *
     * <p>为空时按启用处理 —— 网关侧 `status = 1` 过滤的前提是**新写入的行必须有值**，
     * 故 {@code AppServiceImpl.addIpWhitelist} 会补默认值。</p>
     */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
