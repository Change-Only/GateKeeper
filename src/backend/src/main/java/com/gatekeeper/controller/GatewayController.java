package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gatekeeper.common.Result;
import com.gatekeeper.gateway.GatewayCore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * 网关代理入口 - 统一接收所有 /gateway/** 请求
 *
 * <p>作为 API 网关的统一转发入口，接收所有以 {@code /gateway/**} 打头的请求，
 * 交由 {@link GatewayCore} 完成鉴权、限流、安全检测、加解密与后端转发等核心处理。
 * 支持 GET/POST/PUT/DELETE 四类 HTTP 方法。
 *
 * <p>主要接口路径前缀：{@code /gateway/**}
 * <ul>
 *   <li>任意 /gateway/** 请求均由此处 {@code proxy} 方法统一处理</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/gateway/**")
@RequiredArgsConstructor
@Tag(name = "网关代理", description = "网关代理管理接口")
public class GatewayController {

    private final GatewayCore gatewayCore;

    /**
     * 网关统一转发处理入口
     *
     * <p>成功转发时原样返回后端响应（HTTP 200）；
     * 失败时按业务语义返回对应 HTTP 状态码（401/403/404/429/502/504），
     * 使调用方可按标准 HTTP 语义处理错误。</p>
     *
     * @param request 原始 HTTP 请求（含请求头、请求参数、客户端 IP 等）
     * @param body    请求体（可选，GET 请求可能为空）
     * @return 转发后的响应内容；发生异常时返回统一错误结果
     */
    @Operation(summary = "网关代理转发")
    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<?> proxy(HttpServletRequest request,
                        @RequestBody(required = false) String body) {
        try {
            // 交由网关核心执行完整转发链路：鉴权 -> 限流 -> 安全检测 -> 加解密 -> 转发
            String response = gatewayCore.execute(request, body);
            return ResponseEntity.ok(response);
        } catch (com.gatekeeper.exception.GatewayException e) {
            // 网关业务异常：返回对应 HTTP 状态码（401/403/404/429/502/504 等）
            log.warn("Gateway rejected: status={}, msg={}", e.getCode(), e.getMessage());
            int status = e.getCode();
            HttpStatus httpStatus = HttpStatus.resolve(status);
            if (httpStatus == null) {
                httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            return ResponseEntity.status(httpStatus)
                    .body(Result.error(status, e.getMessage()));
        } catch (Exception e) {
            // 未知异常：不向调用方暴露内部细节
            log.error("Gateway proxy error: type={}, msg={}", e.getClass().getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.error(500, "网关内部错误"));
        }
    }
}
