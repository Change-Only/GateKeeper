package com.gatekeeper.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 安全检测服务（门面）— 异常调用检测的统一入口
 *
 * <p>本类已从「8 职责的上帝类」重构为<b>薄门面</b>：对外保持与重构前完全一致的
 * public 方法签名与 {@code @Async} 异步语义，实际检测逻辑按「单一职责」拆分为
 * 5 个独立检测器 + 2 个共享组件：</p>
 * <ul>
 *   <li>{@link AuthFailDetector}        连续鉴权失败 → 自动封禁</li>
 *   <li>{@link PermissionBreachDetector} 权限越界 → 告警 +（可选）自动封禁</li>
 *   <li>{@link AbnormalParamDetector}    异常入参（SQL 注入/路径穿越/超大 payload）</li>
 *   <li>{@link HighFrequencyDetector}    高频调用（平均 QPS 超限）</li>
 *   <li>{@link OffHoursDetector}         异常时段调用（默认 23:00-06:00）</li>
 *   <li>{@link SecurityEventRecorder}    安全事件落库（共享）</li>
 *   <li>{@link BanExecutor}              自动封禁 + CRITICAL 告警（共享）</li>
 * </ul>
 *
 * <p>调用方（AppAuthHandler / PermissionHandler / AbnormalParamCheckHandler）零改动，
 * 仅依赖本门面，检测器内部变更对外透明。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityDetectionService {

    private final AuthFailDetector authFailDetector;
    private final PermissionBreachDetector permissionBreachDetector;
    private final AbnormalParamDetector abnormalParamDetector;
    private final HighFrequencyDetector highFrequencyDetector;
    private final OffHoursDetector offHoursDetector;
    private final BanExecutor banExecutor;

    /**
     * 记录一次鉴权失败（连续鉴权失败检测）
     *
     * @param ip     客户端 IP
     * @param appKey 应用 AppKey（可为 null）
     * @param reason 失败原因
     */
    @Async
    public void recordAuthFailure(String ip, String appKey, String reason) {
        authFailDetector.detect(ip, appKey, reason);
    }

    /**
     * 记录一次权限越界（权限越界检测）
     *
     * @param appId         越界应用 ID
     * @param appName       应用名称
     * @param ip            客户端 IP
     * @param interfaceId   被越界接口 ID
     * @param interfacePath 被越界接口路径
     */
    @Async
    public void recordPermissionBreach(Long appId, String appName, String ip, Long interfaceId, String interfacePath) {
        permissionBreachDetector.detect(appId, appName, ip, interfaceId, interfacePath);
    }

    /**
     * 记录一次异常入参（异常入参检测）
     *
     * @param ip      客户端 IP
     * @param appId   应用 ID
     * @param appName 应用名称
     * @param pattern 异常特征描述
     */
    @Async
    public void recordAbnormalParam(String ip, Long appId, String appName, String pattern) {
        abnormalParamDetector.detect(ip, appId, appName, pattern);
    }

    /**
     * 高频调用检测
     *
     * @param appId   应用 ID
     * @param appName 应用名称
     */
    @Async
    public void detectHighFrequency(Long appId, String appName) {
        highFrequencyDetector.detect(appId, appName);
    }

    /**
     * 异常时段调用检测
     *
     * @param appId   应用 ID
     * @param appName 应用名称
     * @param ip      客户端 IP
     */
    @Async
    public void detectOffHours(Long appId, String appName, String ip) {
        offHoursDetector.detect(appId, appName, ip);
    }

    /**
     * 自动封禁 IP（全局封禁）——保留的公共 API，委托封禁执行器
     *
     * @param ip          被封禁的 IP
     * @param reason      封禁原因
     * @param durationMin 封禁时长（分钟）
     */
    public void autoBanIp(String ip, String reason, int durationMin) {
        banExecutor.ban(ip, reason, durationMin);
    }
}
