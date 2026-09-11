package com.gatekeeper.security;

import com.gatekeeper.block.BlockExecutor;
import com.gatekeeper.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 异常入参检测器 — 入参匹配可疑特征（SQL 注入 / 路径穿越 / 超大 payload）
 *
 * <p>生成安全事件并推送 WARNING 告警，供运维关注潜在攻击。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AbnormalParamDetector {

    private final SecurityEventRecorder recorder;
    private final AlertService alertService;

    /** 风控动态封禁规则执行器（Spring 注入；缺失时跳过埋点，绝不阻断检测） */
    @Autowired(required = false)
    private BlockExecutor blockExecutor;

    /**
     * 记录一次异常入参
     *
     * @param ip      客户端 IP
     * @param appId   应用 ID
     * @param appName 应用名称
     * @param pattern 匹配到的异常特征描述
     */
    public void detect(String ip, Long appId, String appName, String pattern) {
        recorder.record("ABNORMAL_PARAM",
                "检测到异常入参: " + pattern,
                appId, appName, ip, "入参匹配异常特征");
        alertService.publish("WARNING", "SECURITY", "异常入参检测",
                "应用[" + (appName == null ? "未知" : appName) + "] 检测到异常入参: " + pattern,
                appId, appName, ip);

        // ===== 风控动态封禁规则埋点（fail-open）=====
        try {
            if (blockExecutor != null) {
                // 异常入参本身无累计计数，单次命中按 1 次上报（规则阈值设为 1 即可触发）
                blockExecutor.evaluateAndBan("IP", "SIGNATURE_MISMATCH", ip, 1);
            }
        } catch (Exception ignored) {
            // 埋点失败不影响检测器主流程
        }

        log.warn("Abnormal param detected: ip={}, pattern={}", ip, pattern);
    }
}
