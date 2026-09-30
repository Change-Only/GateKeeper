package com.gatekeeper.job;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.Alert;
import com.gatekeeper.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

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
 *
 * <h3>集群行为：为什么这里不能用分布式锁</h3>
 * <p>本任务的触发条件<b>恰好是 Redis 不可用</b>——而分布式锁本身依赖 Redis，
 * 用它做协调是自相矛盾的（Redis 一挂，要么所有节点都拿不到锁而集体静默、
 * 要么失败放行而集体告警，两种都达不到「只报一次」的目的）。</p>
 *
 * <p>因此去重下沉到 <b>DB 侧</b>：发布前先查 {@code alert} 表，
 * 若窗口内已存在同标题的<b>未处理</b> SYSTEM 告警（说明本集群已有节点报过），
 * 则只记日志、不再重复发布。DB 是 Redis 之外唯一全集群共享的组件，
 * 恰好适合在这个场景里承担协调职责。</p>
 *
 * <p>残余风险（明确记录）：若 DB 与 Redis <b>同时</b>故障，去重查询会失败、
 * 各节点各发一条（此时本查询按「未重复」处理，宁可多发也不漏报）。
 * 双组件同时故障属于重大故障场景，告警重复不是主要矛盾。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisHealthMonitor {

    /** 系统告警来源标识 */
    private static final String SOURCE_SYSTEM = "SYSTEM";
    /** Redis 故障告警标题（去重依据） */
    static final String TITLE_DOWN = "Redis 不可用";
    /** Redis 恢复告警标题（去重依据） */
    static final String TITLE_UP = "Redis 已恢复";
    /** 去重窗口：窗口内已有未处理的同标题告警即视为「已报过」 */
    static final Duration DEDUP_WINDOW = Duration.ofMinutes(10);

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
                if (alreadyPublishedByCluster(TITLE_DOWN, DEDUP_WINDOW)) {
                    // 本集群其他节点已报过，本次只留痕不重复发布
                    alertSent = true;
                    log.warn("Redis health check FAILED - 集群内已有未处理的同标题告警，本次不重复发布");
                } else {
                    alertService.publish("CRITICAL", SOURCE_SYSTEM, TITLE_DOWN,
                            "Redis 连接异常，网关安全防护（限流/封禁/防重放）已降级放行，请尽快恢复",
                            null, null, null);
                    alertSent = true;
                    log.error("Redis health check FAILED - gateway protections degraded (fail-open)");
                }
            }
        } else if (redisDown) {
            // 从故障恢复：发布恢复告警并复位状态
            redisDown = false;
            alertSent = false;
            if (alreadyPublishedByCluster(TITLE_UP, DEDUP_WINDOW)) {
                log.info("Redis health check recovered - 集群内已有同标题恢复告警，本次不重复发布");
            } else {
                alertService.publish("INFO", SOURCE_SYSTEM, TITLE_UP,
                        "Redis 连接恢复正常，网关安全防护已重新生效", null, null, null);
                log.info("Redis health check recovered - protections restored");
            }
        }
    }

    /**
     * 集群侧去重判断：窗口内是否已存在同标题的未处理 SYSTEM 告警。
     *
     * <p>查询失败（如 DB 亦不可用）时返回 {@code false}（＝按未重复处理），
     * 宁可多发一条也不漏报——监控静默比告警重复危险得多。</p>
     *
     * @param title  告警标题
     * @param window 回溯窗口
     * @return true=集群内已报过，无需重复发布
     */
    private boolean alreadyPublishedByCluster(String title, Duration window) {
        try {
            QueryWrapper<Alert> wrapper = new QueryWrapper<>();
            wrapper.eq("source", SOURCE_SYSTEM)
                    .eq("title", title)
                    .eq("status", AlertService.STATUS_UNREAD)
                    .ge("created_at", LocalDateTime.now().minus(window));
            Long count = alertService.count(wrapper);
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("检查重复告警失败（按未重复处理，宁可多发不漏报）: {}", e.getMessage());
            return false;
        }
    }
}
