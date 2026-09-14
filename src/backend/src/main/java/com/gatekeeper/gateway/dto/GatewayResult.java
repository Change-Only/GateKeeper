package com.gatekeeper.gateway.dto;

import lombok.Data;

/**
 * 网关执行结果（状态码 + 响应体） — T13
 *
 * <p><b>为什么要引入它</b>：{@code GatewayCore.execute} 原来只回一个 String，
 * 于是 {@code GatewayController} 只能 {@code ResponseEntity.ok(body)} —— 无论内部发生了什么，
 * 对调用方一律是 HTTP 200。这对"转发"是对的（原有语义：成功转发即 200），
 * 但对 <b>Mock 短路</b>是错的：Mock 的 HTTP 状态码是用户在环境配置里显式填的，
 * 属于「可配置的 Mock 响应」的一部分，不能只写进日志、不告诉调用方
 * （2026-09-14 实测：配了 mockStatus=503，调用方收到的仍是 200）。</p>
 *
 * <p>{@link #mock} 用来区分这两种情形：只有 Mock 短路才把状态码透传给调用方，
 * 真实转发保持既有 200 语义不变（避免顺手改掉存量行为）。</p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@Data
public class GatewayResult {

    /** 期望回给调用方的 HTTP 状态码（Mock 时为配置值；转发时为上游状态码） */
    private final int status;

    /** 响应体（已按接口配置完成返参加密） */
    private final String body;

    /** 本次响应是否来自 Mock 短路（决定状态码要不要透传给调用方） */
    private final boolean mock;

    /**
     * 构造普通（转发）结果。
     *
     * @param status 上游状态码
     * @param body   响应体
     * @return 结果对象
     */
    public static GatewayResult forward(int status, String body) {
        return new GatewayResult(status, body, false);
    }

    /**
     * 构造 Mock 短路结果。
     *
     * @param status 配置的 Mock 状态码
     * @param body   Mock 响应体
     * @return 结果对象
     */
    public static GatewayResult mock(int status, String body) {
        return new GatewayResult(status, body, true);
    }
}
