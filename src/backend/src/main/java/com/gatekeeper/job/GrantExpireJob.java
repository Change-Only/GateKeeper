package com.gatekeeper.job;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.mapper.AppApiGrantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 授权过期定时任务 — T04-A
 *
 * <p>每日 02:00 执行：将 {@code valid_to < 今天} 且 {@code status=1} 的授权批量置为
 * {@code status=2}（已过期）。整个任务包在 try/catch 中，单任务失败不影响其它调度。</p>
 *
 * <h3>集群行为</h3>
 * <p>用<b>按天唯一</b>的锁键（{@code grant-expire:2026-09-30}）+ 25 小时 TTL 保证
 * 「当天恰好有一个节点执行」：抢到锁的节点执行完<b>不释放</b>锁，让键自然过期，
 * 这样即使各节点时钟有秒级差异、或 cron 因 fixedDelay 抖动重复触发，也不会重复执行。</p>
 *
 * <p>策略选 <b>fail-open</b>：本任务是纯幂等 UPDATE（{@code WHERE status=1 AND valid_to < today}），
 * 重复执行第二次命中 0 行，没有任何副作用。因此 Redis 故障时宁可让各节点都跑一遍，
 * 也不要因为协调组件不可用而让「过期授权一直保持有效」——那是有安全风险的。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrantExpireJob {

    /** 任务基础名（分布式锁键前缀） */
    static final String JOB_NAME = "grant-expire";

    /** 日戳锁的存活时长：> 24 小时，确保覆盖当天全部重复触发 */
    private static final Duration LOCK_TTL = Duration.ofHours(25);

    private final AppApiGrantMapper appApiGrantMapper;
    /** 集群任务互斥锁 */
    private final DistributedJobLock jobLock;

    @Scheduled(cron = "0 0 2 * * ?")
    public void expireOverdueGrants() {
        // 按天唯一的键 ⇒ 当天只有一个节点执行；执行后不释放，交给 TTL 过期
        if (!jobLock.tryLock(DistributedJobLock.daily(JOB_NAME), LOCK_TTL, true)) {
            log.debug("GrantExpireJob 今日已由其他节点执行，跳过");
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            UpdateWrapper<AppApiGrant> wrapper = new UpdateWrapper<>();
            wrapper.set("status", 2)
                    .eq("status", 1)
                    .lt("valid_to", today);
            int updated = appApiGrantMapper.update(null, wrapper);
            log.info("GrantExpireJob done: {} grants expired (valid_to < {})", updated, today);
        } catch (Exception e) {
            log.error("GrantExpireJob failed: {}", e.getMessage(), e);
        }
    }
}
