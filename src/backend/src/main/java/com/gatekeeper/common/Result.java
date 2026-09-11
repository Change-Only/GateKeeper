package com.gatekeeper.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结果包装类
 * 所有接口返回统一结构 {code, message, data}，code 遵循 HTTP 语义（200=成功）
 */
@Data
public class Result<T> implements Serializable {

    /** 响应状态码（200=成功，其余为错误码，遵循 HTTP 语义） */
    private int code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    /**
     * 构造无数据的成功响应
     *
     * @return 成功响应
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 构造带数据的成功响应
     *
     * @param data 业务数据
     * @return 成功响应
     */
    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.setCode(200);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    /**
     * 构造指定错误码与提示信息的错误响应
     *
     * @param code    错误码
     * @param message 提示信息
     * @return 错误响应
     */
    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    /**
     * 构造默认 500 错误码的错误响应
     *
     * @param message 提示信息
     * @return 错误响应
     */
    public static <T> Result<T> error(String message) {
        return error(500, message);
    }

    /**
     * 构造 401 未授权响应
     *
     * @param message 提示信息
     * @return 未授权响应
     */
    public static <T> Result<T> unauthorized(String message) {
        return error(401, message);
    }

    /**
     * 构造 403 无权限响应
     *
     * @param message 提示信息
     * @return 无权限响应
     */
    public static <T> Result<T> forbidden(String message) {
        return error(403, message);
    }

    /**
     * 构造 404 资源不存在响应
     *
     * @param message 提示信息
     * @return 资源不存在响应
     */
    public static <T> Result<T> notFound(String message) {
        return error(404, message);
    }

    /**
     * 构造 429 请求过于频繁响应
     *
     * @param message 提示信息
     * @return 限流响应
     */
    public static <T> Result<T> tooManyRequests(String message) {
        return error(429, message);
    }
}
