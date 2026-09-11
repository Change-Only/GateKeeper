package com.gatekeeper.job;

import com.gatekeeper.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis 健康监控 — 网关防护组件（限流/封禁/防重放）的可用性哨兵
 *
 * <p>网关主链路的限流、IP 封禁、Nonce 防重放均依赖 Redis；当 Redis 不可用时这些
 * 防护会按 {@code gatekeeper.redis.fail-open} 策略降级放行（业务不中断，但防护暂时失效）。
 * 本监控每 30 秒探测一次 Redis：</p>
 * <ul>
 *   <li>故障首次检出：发布 CRITICAL 告警（故障期间仅一次，防刷屏），提醒运维尽快恢复；</li>
 *   <li>恢复检出：发布 INFO 告警，提示防护已重新生效。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisHealthMonitor {

    private final StringRedisTemplate redisTemplate;
    private final AlertService alertService;

    /** 当前是否处于 Redis 故障状态（内存态，进程重启后重新探测） */
    private volatile boolean redisDown = false;

    /** 故障期间是否已发送 CRITICAL 告警（防刷屏） */
    private volatile boolean alertSent = false;

    /**
     * 每 30 秒探测 Redis 连通性（固定延迟，探测本身异常不影响调度）
     */
    @Scheduled(fixedDelay = 30000)
    public void check() {
        boolean ok;
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            ok = "PONG".equalsIgnoreCase(connection.ping());
        } catch (Exception e) {
            log.debug("Redis ping failed: {}", e.getMessage());
            ok = false;
        }

        if (!ok) {
            if (!redisDown) {
                // 新故障周期：允许再次告警（上次告警已被处理/恢复过）
                redisDown = true;
                alertSent = false;
            }
            if (!alertSent) {
                alertService.publish("CRITICAL", "SYSTEM", "Redis 不可用",
                        "Redis 连接异常，网关安全防护（限流/封禁/防重放）已降级放行，请尽快恢复",
                        null, null, null);
                alertSent = true;
                log.error("Redis health check FAILED - gateway protections degraded (fail-open)");
            }
        } else if (redisDown) {
            // 从故障恢复：发布恢复告警并复位状态
            redisDown = false;
            alertSent = false;
            alertService.publish("INFO", "SYSTEM", "Redis 已恢复",
                    "Redis 连接恢复正常，网关安全防护已重新生效", null, null, null);
            log.info("Redis health check recovered - protections restored");
        }
    }
}
