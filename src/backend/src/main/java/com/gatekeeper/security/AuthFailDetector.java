package com.gatekeeper.security;

import com.gatekeeper.block.BlockExecutor;
import com.gatekeeper.security.SecurityEventRecorder;
import com.gatekeeper.security.BanExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 鉴权失败检测器 — 连续鉴权失败自动封禁
 *
 * <p>统计「同一 IP + AppKey」在时间窗口内的失败次数，首次失败启动窗口过期，
 * 超过阈值自动封禁来源 IP（默认 5 分钟内失败 10 次）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthFailDetector {

    private final StringRedisTemplate redisTemplate;
    private final SecurityEventRecorder recorder;
    private final BanExecutor banExecutor;

    /** 风控动态封禁规则执行器（Spring 注入；缺失时跳过埋点，绝不阻断检测） */
    @Autowired(required = false)
    private BlockExecutor blockExecutor;

    /** 统计时间窗口（分钟），默认 5 分钟 */
    @Value("${gatekeeper.security.auth-fail-window-min:5}")
    private int windowMin;

    /** 失败次数阈值，超过触发自动封禁，默认 10 次 */
    @Value("${gatekeeper.security.auth-fail-threshold:10}")
    private int threshold;

    /** 自动封禁时长（分钟），默认 60 分钟 */
    @Value("${gatekeeper.security.auto-ban-duration-min:60}")
    private int banDurationMin;

    /**
     * 记录一次鉴权失败并检测是否达到封禁阈值
     *
     * @param ip     客户端 IP
     * @param appKey 应用 AppKey（可为 null）
     * @param reason 失败原因
     */
    public void detect(String ip, String appKey, String reason) {
        if (ip == null) return;
        String key = "auth_fail:" + ip + ":" + (appKey != null ? appKey : "unknown");
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, windowMin, TimeUnit.MINUTES);
        }

        recorder.record("AUTH_FAIL", "鉴权失败: " + reason, null, null, ip, "连续失败" + count + "次");

        if (count != null && count >= threshold) {
            banExecutor.ban(ip, "连续鉴权失败" + count + "次", banDurationMin);
            recorder.record("AUTH_FAIL", "IP " + ip + " 因连续鉴权失败" + count + "次被自动封禁",
                    null, null, ip, "触发自动封禁");
        }

        // 风控埋点（T04-D）：将本次鉴权失败接入动态封禁规则（scope=IP, reasonCode=IP_NOT_ALLOWED）。
        // 失败开放：BlockExecutor 缺失或抛错均不影响既有检测与封禁逻辑。
        try {
            if (blockExecutor != null) {
                int current = count != null ? count.intValue() : 0;
                blockExecutor.evaluateAndBan("IP", "IP_NOT_ALLOWED", ip, current);
            }
        } catch (Exception ignored) {
            // 埋点失败不影响检测器主流程
        }

        log.warn("Auth failure recorded: ip={}, appKey={}, reason={}, count={}", ip, appKey, reason, count);
    }
}
