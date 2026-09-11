package com.gatekeeper.job;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.mapper.AppApiGrantMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 授权过期定时任务 — T04-A
 *
 * <p>每日 02:00 执行：将 {@code valid_to < 今天} 且 {@code status=1} 的授权批量置为
 * {@code status=2}（已过期）。整个任务包在 try/catch 中，单任务失败不影响其它调度。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrantExpireJob {

    private final AppApiGrantMapper appApiGrantMapper;

    @Scheduled(cron = "0 0 2 * * ?")
    public void expireOverdueGrants() {
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
