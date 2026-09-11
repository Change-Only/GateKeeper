package com.gatekeeper.security;

import com.gatekeeper.block.BlockExecutor;
import com.gatekeeper.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 高频调用检测器 — 平均 QPS 超限告警
 *
 * <p>以 Redis 固定窗口计数统计应用在最近 {@code windowSec} 秒内的调用次数，
 * 达到阈值（{@code qps * windowSec}）时记录安全事件并推送限流类告警；
 * 同一窗口仅告警一次（setIfAbsent 去重），避免刷屏。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HighFrequencyDetector {

    private final StringRedisTemplate redisTemplate;
    private final SecurityEventRecorder recorder;
    private final AlertService alertService;

    /** 风控动态封禁规则执行器（Spring 注入；缺失时跳过埋点，绝不阻断检测） */
    @Autowired(required = false)
    private BlockExecutor blockExecutor;

    /** 平均 QPS 阈值，默认 100 */
    @Value("${gatekeeper.security.high-freq-qps:100}")
    private int qps;

    /** 统计窗口（秒），默认 30 秒 */
    @Value("${gatekeeper.security.high-freq-window-sec:30}")
    private int windowSec;

    /**
     * 记录一次调用并检测是否高频超限
     *
     * @param appId   应用 ID
     * @param appName 应用名称
     */
    public void detect(Long appId, String appName) {
        if (appId == null) return;
        try {
            long now = System.currentTimeMillis();
            long windowMs = windowSec * 1000L;
            long windowId = now / windowMs; // 固定窗口编号
            String countKey = "hf_count:" + appId + ":" + windowId;
            Long count = redisTemplate.opsForValue().increment(countKey);
            if (count == 1) {
                redisTemplate.expire(countKey, windowSec * 2, TimeUnit.SECONDS);
            }

            long threshold = (long) qps * windowSec;
            if (count != null && count >= threshold) {
                String alertKey = "hf_alert:" + appId + ":" + windowId;
                Boolean firstAlert = redisTemplate.opsForValue().setIfAbsent(alertKey, "1", windowSec, TimeUnit.SECONDS);
                if (firstAlert != null && firstAlert) {
                    recorder.record("HIGH_FREQUENCY",
                            "应用[" + appName + "]高频调用：" + count + "次/" + windowSec + "秒（阈值" + threshold + "）",
                            appId, appName, null, "平均QPS>" + qps + " 持续" + windowSec + "秒");
                    alertService.publish("WARNING", "RATE_LIMIT", "高频调用告警",
                            "应用[" + appName + "]高频调用：" + count + "次/" + windowSec + "秒（阈值" + threshold + "）",
                            appId, appName, null);
                    log.warn("High frequency detected: appId={}, count={}/{}s", appId, count, windowSec);
                }
            }

            // ===== 风控动态封禁规则埋点（fail-open，额外保护）=====
            try {
                if (blockExecutor != null) {
                    int current = count != null ? count.intValue() : 0;
                    blockExecutor.evaluateAndBan("APP", "RATE_LIMIT_EXCEEDED", String.valueOf(appId), current);
                }
            } catch (Exception ignored) {
                // 埋点失败不影响检测器主流程
            }
        } catch (Exception e) {
            log.error("detectHighFrequency failed: {}", e.getMessage());
        }
    }
}
