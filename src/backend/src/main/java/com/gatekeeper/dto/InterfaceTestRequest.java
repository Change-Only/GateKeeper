package com.gatekeeper.dto;

import lombok.Data;

import java.util.Map;

/**
 * 接口试调测试请求 — T13「新增接口测试功能」
 *
 * <p>支持两种模式：
 * <ul>
 *   <li>{@link #MODE_DIRECT} 直连后端：用「生效配置的服务前缀 + 接口URI」直接发真实请求。
 *       不需要应用凭证，专门验证**上游是否通、配置对不对**。</li>
 *   <li>{@link #MODE_GATEWAY} 走网关：用某个应用的 AppKey + 签名调用 {@code /gateway/**}，
 *       把鉴权 / 限流 / 权限 / Mock / 日志**整条链路**都跑一遍。</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@Data
public class InterfaceTestRequest {

    /** 直连后端模式 */
    public static final String MODE_DIRECT = "DIRECT";
    /** 走网关模式 */
    public static final String MODE_GATEWAY = "GATEWAY";

    /** 环境编码（dev/test/pre/prod），空则默认 prod */
    private String envCode;

    /** 模式：DIRECT（默认） / GATEWAY */
    private String mode;

    /** 走网关模式使用的应用 ID；为空时自动挑选「对该接口有有效授权」的应用 */
    private Long appId;

    /** 请求体（GET 可空） */
    private String body;

    /** 内容类型，默认 application/json */
    private String contentType;

    /** 额外请求头（可选） */
    private Map<String, String> headers;

    /** 归一化模式：空或未知一律按直连处理 */
    public String normalizedMode() {
        return MODE_GATEWAY.equalsIgnoreCase(mode == null ? "" : mode.trim()) ? MODE_GATEWAY : MODE_DIRECT;
    }
}
