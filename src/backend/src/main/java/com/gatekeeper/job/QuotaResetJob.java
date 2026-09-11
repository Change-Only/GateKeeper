package com.gatekeeper.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 日配额重置定时任务 — T04-A（尽力而为 / fail-safe）
 *
 * <p>每日 00:00 执行：对应用日配额做重置。当前代码库尚未引入 app_quota 表/Mapper，
 * 故此处仅记录日志、保证任务幂等且可恢复，待后续迭代接入真实重置逻辑。</p>
 */
@Slf4j
@Component
public class QuotaResetJob {

    @Scheduled(cron = "0 0 0 * * ?")
    public void resetDailyQuota() {
        try {
            log.info("QuotaResetJob: daily quota reset start (best-effort)");
            // 当前代码库无 app_quota 表/Mapper：仅记录日志，保持简单与韧性，
            // 待 app_quota 落地后再实现真实重置（UPDATE app_quota SET used=0 WHERE date < CURDATE()）。
            log.info("QuotaResetJob: no app_quota mapper configured, skipped");
        } catch (Exception e) {
            log.error("QuotaResetJob failed: {}", e.getMessage(), e);
        }
    }
}
