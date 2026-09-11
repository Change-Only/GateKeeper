package com.gatekeeper.security;

import com.gatekeeper.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 自动封禁执行器 — 各检测器的共享封禁组件
 *
 * <p>从 SecurityDetectionService 抽取（原 autoBanIp 方法）。检测器判定达到封禁阈值后，
 * 调用本执行器完成两件事：</p>
 * <ol>
 *   <li>写入 Redis 的 {@code ip_ban:{ip}:global} 键（带 TTL），网关实时查询 O(1) 生效；</li>
 *   <li>发布 CRITICAL 等级告警，供运维实时关注。</li>
 * </ol>
 *
 * <p>封禁执行与检测逻辑解耦后，未来如改封禁存储（分布式限流组件/本地缓存）只需改本类。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BanExecutor {

    private final StringRedisTemplate redisTemplate;
    private final AlertService alertService;

    /**
     * 自动封禁 IP（全局封禁，对所有应用生效）
     *
     * @param ip          被封禁的 IP
     * @param reason      封禁原因
     * @param durationMin 封禁时长（分钟）
     */
    public void ban(String ip, String reason, int durationMin) {
        String banKey = "ip_ban:" + ip + ":global";
        redisTemplate.opsForValue().set(banKey, reason, durationMin, TimeUnit.MINUTES);
        alertService.publish("CRITICAL", "SECURITY", "IP 自动封禁",
                "IP " + ip + " 因「" + reason + "」被自动封禁 " + durationMin + " 分钟", null, null, ip);
        log.warn("IP auto-banned: ip={}, reason={}, duration={}min", ip, reason, durationMin);
    }
}
