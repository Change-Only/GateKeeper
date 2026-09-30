package com.gatekeeper.job;

import com.gatekeeper.alarm.AlarmRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
 * <h3>集群行为（分布式锁）</h3>
 * <p>两个评估方法都以 <b>fail-safe</b> 方式持有 {@link DistributedJobLock}：
 * 同一时刻全集群只有一个节点在评估，其余节点直接跳过。这样做的收益有两层：</p>
 * <ol>
 *   <li><b>省掉 N 倍无效计算</b>——否则每个副本每 10s 都要把全部规则查一遍库；</li>
 *   <li><b>消除重复告警</b>——本任务是<b>有外部副作用</b>的（落库告警 + 发通知），
 *       而「静默键」的检查与写入之间存在竞态窗口，多节点并发时仍可能同时越过检查。</li>
 * </ol>
 * <p>选 fail-safe 而非 fail-open 的原因：Redis 故障时静默键读写全部失效，
 * 若此时放行所有节点执行，会退化成「每个 tick 每节点各发一遍同样告警」的告警风暴；
 * 而少评估几轮是无害的——Redis 恢复后下一轮立即照常。</p>
 *
 * <p><strong>fail-open（针对业务异常）</strong>：评估逻辑整体包 try/catch，任何异常仅记录日志，
 * 绝不向上抛出，避免破坏 Spring 调度线程（调度永不中断）。</p>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlarmEvaluateJob {

    /** 实时评估的任务名（分布式锁键） */
    static final String JOB_REALTIME = "alarm-realtime";
    /** 离线评估的任务名（分布式锁键） */
    static final String JOB_OFFLINE = "alarm-offline";

    /** 实时评估持锁时长：远大于单轮评估耗时，仅作持锁节点宕机时的兜底回收 */
    private static final Duration REALTIME_LOCK_TTL = Duration.ofSeconds(60);
    /** 离线评估持锁时长 */
    private static final Duration OFFLINE_LOCK_TTL = Duration.ofMinutes(15);

    private final AlarmRuleService alarmRuleService;
    /** 集群任务互斥锁（单节点部署时该锁恒为「总能抢到」，行为与改造前一致） */
    private final DistributedJobLock jobLock;

    /**
     * 实时评估（对象级规则）。每 10s 执行一次。
     */
    @Scheduled(fixedDelay = 10000)
    public void realtimeEvaluate() {
        if (!jobLock.tryLock(JOB_REALTIME, REALTIME_LOCK_TTL, false)) {
            return; // 其他节点正在评估（或 Redis 不可用时的 fail-safe 跳过）
        }
        try {
            alarmRuleService.evaluateRealtime();
        } catch (Exception e) {
            log.error("[AlarmEvaluateJob] realtime evaluate error (fail-open)", e);
        } finally {
            jobLock.unlock(JOB_REALTIME);
        }
    }

    /**
     * 离线评估（平台全局规则）。每 5 分钟执行一次。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void offlineEvaluate() {
        if (!jobLock.tryLock(JOB_OFFLINE, OFFLINE_LOCK_TTL, false)) {
            return; // 其他节点正在评估（或 Redis 不可用时的 fail-safe 跳过）
        }
        try {
            alarmRuleService.evaluateOffline();
        } catch (Exception e) {
            log.error("[AlarmEvaluateJob] offline evaluate error (fail-open)", e);
        } finally {
            jobLock.unlock(JOB_OFFLINE);
        }
    }
}
