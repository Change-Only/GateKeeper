package com.gatekeeper.gateway.handler;

import com.gatekeeper.entity.ApiCallLog;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.util.DesensitizeUtil;
import com.gatekeeper.mapper.ApiCallLogMapper;
import com.gatekeeper.security.SecurityEventRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Step 9: 日志记录 (异步)
 *
 * <p>记录每次 API 调用的完整链路信息：调用方应用、接口、时间、入参、响应、
 * 耗时、状态码、IP、加密算法、限流/拦截标记。由 GatewayCore 在 finally 中
 * 通过 {@code @Async} 异步调用，不阻塞主链路。</p>
 *
 * <p>T04-D 异步埋点增强：当本次调用被网关拦截（{@code ctx.isBlocked()}）时，
 * 在同一 {@code @Async} 路径内补记一条安全事件（GATEWAY_BLOCK），便于风控侧聚合。
 * 仅使用 GatewayContext 既有字段，失败仅记日志、不影响主流程。</p>
 */
@Slf4j
@Component
@Order(9)
@RequiredArgsConstructor
public class LogHandler implements GatewayHandler {

    private final ApiCallLogMapper callLogMapper;

    /** 安全事件记录器（异步埋点用，Spring 注入；非必须，缺失则跳过） */
    @Autowired(required = false)
    private SecurityEventRecorder eventRecorder;

    /**
     * 责任链占位方法（实际日志在 GatewayCore 异步调用 recordLog 完成）
     *
     * @param ctx 网关上下文
     */
    @Override
    public void handle(GatewayContext ctx) {
        // 不在责任链中执行，由 GatewayCore 异步调用
    }

    /**
     * 异步记录调用日志到 api_call_log 表
     * <p>无论调用成功、限流还是被拦截，只要进入网关都会记录。</p>
     *
     * @param ctx 网关上下文（含完整调用信息）
     */
    public void recordLog(GatewayContext ctx) {
        try {
            ApiCallLog logEntry = new ApiCallLog();
            logEntry.setAppId(ctx.getAppId());
            logEntry.setAppName(ctx.getAppName());
            logEntry.setInterfaceId(ctx.getInterfaceId());
            logEntry.setInterfacePath(ctx.getInterfacePath());
            logEntry.setRequestMethod(ctx.getMethod());
            logEntry.setRequestTime(LocalDateTime.now());
            // 入参/响应截断至 4000 字符并脱敏（手机号/身份证/Token 等），防止超大报文与敏感数据落库
            logEntry.setRequestParams(truncate(DesensitizeUtil.mask(ctx.getRequestBody()), 4000));
            logEntry.setResponseData(truncate(DesensitizeUtil.mask(ctx.getResponseBody()), 4000));
            logEntry.setResponseStatus(ctx.getResponseStatus() != null ? ctx.getResponseStatus() : 500);
            logEntry.setCostTime(ctx.getCostTime());
            logEntry.setClientIp(ctx.getClientIp());
            logEntry.setEncryptionAlgorithm(ctx.getEncryptionAlgorithm());
            logEntry.setIsRateLimited(ctx.isRateLimited());
            logEntry.setIsBlocked(ctx.isBlocked());
            logEntry.setBlockReason(ctx.getBlockReason());
            logEntry.setCreatedAt(LocalDateTime.now());

            callLogMapper.insert(logEntry);

            // 异步埋点：被拦截调用补记安全事件（与 call-log 同处 @Async 路径，fail-safe）
            recordBlockEvent(ctx);
        } catch (Exception e) {
            // 日志记录失败不影响主流程
            log.error("Failed to record call log", e);
        }
    }

    /**
     * 异步埋点：当本次调用被网关拦截时，在 {@code @Async} 路径内补记一条安全事件。
     *
     * <p>仅使用 GatewayContext 既有字段（isBlocked / getBlockReason / getAppId /
     * getAppName / getClientIp），绝不新增 ctx 字段；失败仅记日志，不影响主流程。</p>
     *
     * @param ctx 网关上下文
     */
    private void recordBlockEvent(GatewayContext ctx) {
        try {
            if (eventRecorder == null || !ctx.isBlocked()) {
                return;
            }
            String reason = ctx.getBlockReason();
            if (reason == null || reason.isEmpty()) {
                reason = "UNKNOWN";
            }
            eventRecorder.record("GATEWAY_BLOCK",
                    "网关拦截调用: " + reason,
                    ctx.getAppId(), ctx.getAppName(), ctx.getClientIp(), reason);
        } catch (Exception e) {
            // 异步埋点失败绝不影响主流程（fail-safe）
            log.error("Failed to record async block event (non-critical)", e);
        }
    }

    /**
     * 字符串截断，超出指定长度追加省略号
     *
     * @param str    原始字符串
     * @param maxLen 最大长度
     * @return 截断后的字符串（null 原样返回）
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
