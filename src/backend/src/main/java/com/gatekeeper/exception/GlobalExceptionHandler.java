package com.gatekeeper.exception;

import com.gatekeeper.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * 统一捕获各类异常并转换为 Result 统一响应，避免异常堆栈直接暴露给调用方
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理网关业务异常，返回其携带的业务错误码与提示信息，
     * 并同步设置 HTTP 状态码（401/403/404/429/502/504 等），
     * 使调用方可按标准 HTTP 语义感知失败，而非统一 200。
     *
     * @param e 网关业务异常
     * @return 带对应 HTTP 状态码的统一错误响应
     */
    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Result<Void>> handleGatewayException(GatewayException e) {
        log.warn("Gateway exception: code={}, msg={}", e.getCode(), e.getMessage());
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status).body(Result.error(e.getCode(), e.getMessage()));
    }

    /**
     * 处理 @Valid 校验失败异常，返回首个字段的校验提示
     *
     * @param e 参数校验异常
     * @return 统一错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        return Result.error(400, msg);
    }

    /**
     * 处理表单参数绑定异常
     *
     * @param e 参数绑定异常
     * @return 统一错误响应
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError != null ? fieldError.getDefaultMessage() : "参数绑定失败";
        return Result.error(400, msg);
    }

    /**
     * 处理非法参数异常
     *
     * @param e 非法参数异常
     * @return 统一错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Result.error(400, e.getMessage());
    }

    /**
     * 处理请求体无法解析异常（JSON 语法错误、字段类型不匹配、必填体缺失等）。
     *
     * <p>这类问题由<b>调用方</b>引起，属客户端错误，必须返回 400，而不是落到
     * 兜底的 {@link #handleException} 变成 500「系统繁忙」——否则调用方无法区分
     * 「我传错了」与「服务端炸了」，会把参数问题当成故障上报。
     * 触发场景实例：{@code {"groupId": [12]}}（数组塞进 Long 字段）在 T18 导入
     * 链路上曾表现为 500，实为请求体类型错误。</p>
     *
     * <p>安全：对外只给通用提示；具体解析细节（含内部 DTO 类名与字段引用链）
     * 仅写入日志，遵循本类「不向调用方暴露内部异常细节」的一贯口径。</p>
     *
     * @param e 请求体解析异常
     * @return 400 统一错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Unreadable request body: {}", e.getMessage());
        return Result.error(400, "请求体格式不正确，请检查 JSON 语法及字段类型");
    }

    /**
     * 兜底处理未预期的系统异常。
     * 安全：仅返回通用提示，不向调用方暴露内部异常细节（堆栈/类名/SQL 等）
     *
     * @param e 系统异常
     * @return 统一错误响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("Unexpected error: type={}, msg={}", e.getClass().getName(), e.getMessage(), e);
        return Result.error(500, "系统繁忙，请稍后重试");
    }
}
