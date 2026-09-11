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
 * 权限越界检测器 — 应用调用未授权接口的检测与告警
 *
 * <p>统计应用在窗口内尝试调用未授权接口的次数，生成安全事件与告警；
 * 达到阈值且开启自动封禁时，自动封禁来源 IP（默认 10 分钟内 20 次，自动封禁默认关闭）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionBreachDetector {

    private final StringRedisTemplate redisTemplate;
    private final SecurityEventRecorder recorder;
    private final AlertService alertService;
    private final BanExecutor banExecutor;

    /** 风控动态封禁规则执行器（Spring 注入；缺失时跳过埋点，绝不阻断检测） */
    @Autowired(required = false)
    private BlockExecutor blockExecutor;

    /** 统计窗口（分钟），默认 10 分钟 */
    @Value("${gatekeeper.security.perm-breach-window-min:10}")
    private int windowMin;

    /** 越界次数阈值，默认 20 次 */
    @Value("${gatekeeper.security.perm-breach-threshold:20}")
    private int threshold;

    /** 是否自动封禁来源 IP，默认关闭（避免误封） */
    @Value("${gatekeeper.security.perm-breach-auto-ban:false}")
    private boolean autoBan;

    /** 自动封禁时长（分钟），默认 60 分钟 */
    @Value("${gatekeeper.security.perm-breach-ban-duration-min:60}")
    private int banDurationMin;

    /**
     * 记录一次权限越界并检测是否达到告警/封禁阈值
     *
     * @param appId         越界应用 ID
     * @param appName       应用名称
     * @param ip            客户端 IP
     * @param interfaceId   被越界调用的接口 ID
     * @param interfacePath 被越界调用的接口路径
     */
    public void detect(Long appId, String appName, String ip, Long interfaceId, String interfacePath) {
        String key = "perm_breach:" + appId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, windowMin, TimeUnit.MINUTES);
        }

        recorder.record("PERMISSION_BREACH",
                "应用[" + appName + "]尝试调用未授权接口: " + interfacePath,
                appId, appName, ip, windowMin + "分钟内尝试" + count + "次");

        if (count != null && count >= threshold) {
            alertService.publish("WARNING", "SECURITY", "权限越界预警",
                    "应用[" + appName + "]在" + windowMin + "分钟内尝试调用未授权接口" + count + "次",
                    appId, appName, ip);
        }

        if (autoBan && count != null && count >= threshold && ip != null) {
            banExecutor.ban(ip, "权限越界" + count + "次", banDurationMin);
            recorder.record("PERMISSION_BREACH",
                    "应用[" + appName + "]权限越界" + count + "次，来源 IP " + ip + " 被自动封禁",
                    appId, appName, ip, "触发自动封禁");
        }

        // ===== 风控动态封禁规则埋点（fail-open）=====
        try {
            if (blockExecutor != null && appId != null) {
                int current = count != null ? count.intValue() : 0;
                blockExecutor.evaluateAndBan("APP", "SIGNATURE_MISMATCH", String.valueOf(appId), current);
            }
        } catch (Exception ignored) {
            // 埋点失败不影响检测器主流程
        }

        log.warn("Permission breach: appId={}, ip={}, interface={}, count={}", appId, ip, interfacePath, count);
    }
}
