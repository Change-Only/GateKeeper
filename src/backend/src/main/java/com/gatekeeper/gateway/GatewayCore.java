package com.gatekeeper.gateway;

import com.gatekeeper.entity.ApiCallLog;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.gateway.dto.GatewayResult;
import com.gatekeeper.gateway.handler.EncryptionHandler;
import com.gatekeeper.gateway.handler.GatewayHandler;
import com.gatekeeper.gateway.handler.LogHandler;
import com.gatekeeper.mapper.ApiCallLogMapper;
import com.gatekeeper.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 网关核心调度器 - 协调责任链执行
 *
 * <p>职责：</p>
 * <ul>
 *   <li>启动时按 {@link Order} 注解排序收集所有 {@link GatewayHandler}，构建处理责任链</li>
 *   <li>接收 /gateway/** 请求，按序执行 应用校验 → IP白名单 → 封禁检查 → 限流 → 权限 → 入参解密 → 转发</li>
 *   <li>链路执行完成后统一处理响应加密、耗时统计、异步日志与并发计数回收</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayCore {

    /** 容器中所有网关处理器（Spring 自动注入） */
    private final List<GatewayHandler> handlers;
    /** 加解密处理器（响应加密需要单独调用） */
    private final EncryptionHandler encryptionHandler;
    /** 日志处理器（最后异步执行） */
    private final LogHandler logHandler;
    /** 调用日志 Mapper */
    private final ApiCallLogMapper callLogMapper;
    /** Redis（并发计数回收） */
    private final StringRedisTemplate redisTemplate;
    /** 告警服务（网关内部异常时推送 CRITICAL 告警） */
    private final AlertService alertService;

    /** 是否信任 X-Forwarded-For 头（仅网关部署在可信代理之后时开启） */
    @Value("${gatekeeper.security.trust-xff:false}")
    private boolean trustXff;

    /** 按 @Order 排序后的处理器链 */
    private List<GatewayHandler> sortedHandlers;

    /**
     * 初始化责任链：按处理器上的 {@code @Order} 注解升序排序。
     *
     * <p>实际执行顺序（T19 校正注释，此前此处写作「白名单→封禁→认证→…」，把封禁排在了认证之前，
     * 与 {@code @Order} 注解不符，属笔误；权威顺序以各 Handler 的 {@code @Order} 为准）：</p>
     * <pre>
     * 0  SysAccessWhitelistHandler  系统级访问白名单
     * 1  AppAuthHandler             应用认证（AppKey/状态/到期/签名/时间戳/Nonce/环境）
     * 2  IpWhitelistHandler         应用级 IP 白名单
     * 3  IpBanCheckHandler          封禁检查
     * 4  RateLimitHandler           限流（QPS/并发/日配额）
     * 5  PermissionHandler          接口匹配 + 生效环境配置 + 授权校验
     * 6  EncryptionHandler          入参解密
     * 6  VersionRouteHandler        灰度版本路由（与上者 @Order 同值，先后不作契约）
     * 7  AbnormalParamCheckHandler  异常入参检测
     * 8  ForwardHandler             转发上游（或 Mock 短路）
     * 9  LogHandler                 调用日志（循环内跳过，finally 中异步执行）
     * </pre>
     */
    @PostConstruct
    public void init() {
        sortedHandlers = handlers.stream()
                .sorted(Comparator.comparingInt(h -> {
                    Order order = h.getClass().getAnnotation(Order.class);
                    return order != null ? order.value() : 0;
                }))
                .collect(Collectors.toList());
        log.info("Gateway handlers initialized: {}", sortedHandlers.stream()
                .map(h -> h.getClass().getSimpleName())
                .collect(Collectors.joining(" -> ")));
    }

    /**
     * 执行网关请求处理完整链路（兼容入口，只回响应体）。
     *
     * <p>保留原签名供既有调用方/测试使用；需要拿到 HTTP 状态码（Mock 场景）请用
     * {@link #executeWithStatus(HttpServletRequest, String)}。</p>
     *
     * @param request HTTP 请求（用于提取 IP、Header 等上下文信息）
     * @param body    请求体原始内容（可能是密文）
     * @return 处理后的响应体（若接口配置返参加密则为密文）
     * @throws GatewayException 链路上任一环节校验失败时抛出（带对应 HTTP 状态码与原因）
     */
    public String execute(HttpServletRequest request, String body) {
        return executeWithStatus(request, body).getBody();
    }

    /**
     * 执行网关请求处理完整链路，并返回「状态码 + 响应体」。
     *
     * <p>T13 新增：Mock 短路的 HTTP 状态码是用户在环境配置里显式配置的，
     * 属于「可配置的 Mock 响应」的一部分，必须能回给调用方。
     * 原 {@link #execute(HttpServletRequest, String)} 只回 String，
     * 导致 {@code GatewayController} 一律返回 200（实测：配了 mockStatus=503 仍收到 200）。</p>
     *
     * @param request HTTP 请求（用于提取 IP、Header 等上下文信息）
     * @param body    请求体原始内容（可能是密文）
     * @return 网关执行结果（含状态码、响应体、是否 Mock 短路）
     * @throws GatewayException 链路上任一环节校验失败时抛出（带对应 HTTP 状态码与原因）
     */
    public GatewayResult executeWithStatus(HttpServletRequest request, String body) {
        GatewayContext ctx = GatewayContext.from(request, trustXff);
        ctx.setRequestBody(body);

        try {
            // 执行责任链 (不含 LogHandler, LogHandler 最后异步执行)
            for (GatewayHandler handler : sortedHandlers) {
                if (handler instanceof LogHandler) continue;
                handler.handle(ctx);
            }

            // 响应加密：若接口配置了返参加密，在此统一加密
            String response = encryptionHandler.encryptResponse(ctx);
            int status = ctx.getResponseStatus() != null ? ctx.getResponseStatus() : 200;
            return ctx.isMockResponse()
                    ? GatewayResult.mock(status, response)
                    : GatewayResult.forward(status, response);

        } catch (GatewayException e) {
            // 链路拦截：记录拦截标记与原因后继续向上抛出，由全局异常处理器转成错误响应
            ctx.setBlocked(true);
            ctx.setBlockReason(e.getMessage());
            throw e;
        } catch (Exception e) {
            // 未知异常：同样标记拦截，避免日志漏记
            ctx.setBlocked(true);
            ctx.setBlockReason("Internal error: " + e.getMessage());
            // 网关内部异常属于严重运营告警，推送 CRITICAL 告警（失败不影响主流程）
            try {
                alertService.publish("CRITICAL", "GATEWAY", "网关内部错误",
                        "网关处理请求时发生内部异常: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                        ctx.getAppId(), ctx.getAppName(), ctx.getClientIp());
            } catch (Exception ignore) {
                // 告警写入失败不影响主流程
            }
            log.error("Gateway internal error", e);
            throw new RuntimeException(e);
        } finally {
            // 无论成功失败都记录耗时并异步落库日志
            ctx.setCostTime((int) (System.currentTimeMillis() - ctx.getStartTime()));
            // 异步记录日志
            asyncLog(ctx);
            // 并发计数-1（与 RateLimitHandler 中 +1 配对，防止并发数泄漏）
            if (ctx.getAppId() != null) {
                decrementConcurrent(ctx.getAppId());
            }
        }
    }

    /**
     * 异步记录调用日志，不阻塞主链路
     *
     * @param ctx 网关上下文（含完整请求/响应/耗时/拦截信息）
     */
    @Async
    public void asyncLog(GatewayContext ctx) {
        try {
            logHandler.recordLog(ctx);
        } catch (Exception e) {
            log.error("Async log failed", e);
        }
    }

    /**
     * 请求结束后回收并发计数
     * <p>与 RateLimitHandler 中 +1 配对，防止并发数只增不减导致误限流</p>
     *
     * @param appId 应用 ID
     */
    private void decrementConcurrent(Long appId) {
        try {
            String concurrentKey = "rate_limit:concurrent:" + appId;
            // 计数归零后不再递减，避免负值
            Long current = redisTemplate.opsForValue().decrement(concurrentKey);
            if (current != null && current < 0) {
                redisTemplate.opsForValue().set(concurrentKey, "0");
            }
        } catch (Exception e) {
            // Redis 异常不阻塞主流程（并发计数误差自愈于下一窗口）
            log.warn("decrementConcurrent failed for appId={}: {}", appId, e.getMessage());
        }
    }
}
