package com.gatekeeper.exception;

import com.gatekeeper.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
