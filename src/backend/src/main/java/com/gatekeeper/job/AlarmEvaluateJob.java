package com.gatekeeper.job;

import com.gatekeeper.alarm.AlarmRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 告警评估定时任务 — T04-C 告警域
 *
 * <h3>调度</h3>
 * <ul>
 *   <li>实时评估：每 10s（{@code fixedDelay=10000}），评估 scopeType=1（按对象）启用规则；
 *       委托 {@link AlarmRuleService#evaluateRealtime()} 执行。</li>
 *   <li>离线评估：每 5 分钟（cron 表达式 0 0/5 * * * ?），评估 scopeType=2（平台全局）启用规则；
 *       委托 {@link AlarmRuleService#evaluateOffline()} 执行。</li>
 * </ul></p>
 *
 * <p><strong>fail-open</strong>：评估逻辑整体包 try/catch，任何异常仅记录日志，
 * 绝不向上抛出，避免破坏 Spring 调度线程（调度永不中断）。</p>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlarmEvaluateJob {

    private final AlarmRuleService alarmRuleService;

    /**
     * 实时评估（对象级规则）。每 10s 执行一次。
     */
    @Scheduled(fixedDelay = 10000)
    public void realtimeEvaluate() {
        try {
            alarmRuleService.evaluateRealtime();
        } catch (Exception e) {
            log.error("[AlarmEvaluateJob] realtime evaluate error (fail-open)", e);
        }
    }

    /**
     * 离线评估（平台全局规则）。每 5 分钟执行一次。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void offlineEvaluate() {
        try {
            alarmRuleService.evaluateOffline();
        } catch (Exception e) {
            log.error("[AlarmEvaluateJob] offline evaluate error (fail-open)", e);
        }
    }
}
