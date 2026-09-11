package com.gatekeeper.exception;

import lombok.Getter;

/**
 * 网关业务异常
 * 携带业务错误码，用于网关鉴权、限流、转发等环节主动抛出，由全局异常处理器统一转换为响应
 */
@Getter
public class GatewayException extends RuntimeException {

    /** 业务错误码（遵循 HTTP 语义） */
    private final int code;

    /**
     * 构造带错误码与提示信息的业务异常
     *
     * @param code    错误码
     * @param message 提示信息
     */
    public GatewayException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造 401 未授权异常
     *
     * @param message 提示信息
     * @return 未授权异常
     */
    public static GatewayException unauthorized(String message) {
        return new GatewayException(401, message);
    }

    /**
     * 构造 403 无权限异常
     *
     * @param message 提示信息
     * @return 无权限异常
     */
    public static GatewayException forbidden(String message) {
        return new GatewayException(403, message);
    }

    /**
     * 构造 404 资源不存在异常
     *
     * @param message 提示信息
     * @return 资源不存在异常
     */
    public static GatewayException notFound(String message) {
        return new GatewayException(404, message);
    }

    /**
     * 构造 429 请求过于频繁异常
     *
     * @param message 提示信息
     * @return 限流异常
     */
    public static GatewayException tooManyRequests(String message) {
        return new GatewayException(429, message);
    }

    /**
     * 构造 400 参数错误异常
     *
     * @param message 提示信息
     * @return 参数错误异常
     */
    public static GatewayException badRequest(String message) {
        return new GatewayException(400, message);
    }

    /**
     * 构造 502 后端服务异常
     *
     * @param message 提示信息
     * @return 后端服务异常
     */
    public static GatewayException badGateway(String message) {
        return new GatewayException(502, message);
    }

    /**
     * 构造 504 网关超时异常
     *
     * @param message 提示信息
     * @return 网关超时异常
     */
    public static GatewayException gatewayTimeout(String message) {
        return new GatewayException(504, message);
    }
}
