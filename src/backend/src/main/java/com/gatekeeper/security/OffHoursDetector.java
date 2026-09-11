package com.gatekeeper.security;

import com.gatekeeper.block.BlockExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 异常时段调用检测器 — 非工作时间（默认 23:00-06:00）出现调用告警
 *
 * <p>支持跨天时段（23 点至次日 6 点）。按「应用 + 日期 + 小时」维度去重，
 * 每应用每小时最多告警一次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OffHoursDetector {

    private final StringRedisTemplate redisTemplate;
    private final SecurityEventRecorder recorder;

    /** 风控动态封禁规则执行器（Spring 注入；缺失时跳过埋点，绝不阻断检测） */
    @Autowired(required = false)
    private BlockExecutor blockExecutor;

    /** 异常时段起始小时（0-23），默认 23 */
    @Value("${gatekeeper.security.off-hours-start:23}")
    private int startHour;

    /** 异常时段结束小时（0-23），默认 6 */
    @Value("${gatekeeper.security.off-hours-end:6}")
    private int endHour;

    /**
     * 记录一次调用并检测是否处于异常时段
     *
     * @param appId   应用 ID
     * @param appName 应用名称
     * @param ip      客户端 IP
     */
    public void detect(Long appId, String appName, String ip) {
        if (appId == null) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            int hour = now.getHour();
            if (!isOffHours(hour, startHour, endHour)) return;

            String alertKey = "oh_alert:" + appId + ":" + LocalDate.now() + ":" + hour;
            Boolean firstAlert = redisTemplate.opsForValue().setIfAbsent(alertKey, "1", 2, TimeUnit.HOURS);
            if (firstAlert != null && firstAlert) {
                recorder.record("OFF_HOURS",
                        "应用[" + appName + "]在异常时段(" + startHour + ":00-" + endHour + ":00)发起调用",
                        appId, appName, ip, "异常时段调用检测");
                log.warn("Off-hours call detected: appId={}, hour={}", appId, hour);

                // ===== 风控动态封禁规则埋点（fail-open）=====
                try {
                    if (blockExecutor != null) {
                        blockExecutor.evaluateAndBan("APP", "IP_NOT_ALLOWED", String.valueOf(appId), 1);
                    }
                } catch (Exception ignored) {
                    // 埋点失败不影响检测器主流程
                }
            }
        } catch (Exception e) {
            log.error("detectOffHours failed: {}", e.getMessage());
        }
    }

    /**
     * 判断给定小时是否处于异常时段（纯函数，便于单元测试）
     *
     * @param hour  当前小时（0-23）
     * @param start 起始小时（含）
     * @param end   结束小时（不含，区间为 [start, end)）
     * @return true 表示处于异常时段
     */
    static boolean isOffHours(int hour, int start, int end) {
        if (start <= end) {
            // 同一天内时段，如 08:00-18:00
            return hour < start || hour >= end;
        }
        // 跨天时段，如 23:00-06:00
        return hour >= start || hour < end;
    }
}
