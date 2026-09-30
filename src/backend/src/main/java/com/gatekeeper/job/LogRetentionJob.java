package com.gatekeeper.job;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiCallLog;
import com.gatekeeper.mapper.ApiCallLogMapper;
import com.gatekeeper.service.ExportTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 日志与导出文件保留清理定时任务
 *
 * <p>每日凌晨执行两项清理（fail-safe，单项失败不影响另一项）：</p>
 * <ol>
 *   <li><b>调用日志归档</b>：删除超过 {@code gatekeeper.log.retention-days}（默认 90 天）的
 *       调用日志，防止日志表无限膨胀拖垮查询与存储；</li>
 *   <li><b>导出文件清理</b>：删除已完成（SUCCESS/FAILED）超过 7 天的导出文件与任务记录，
 *       防止异步下载中心的磁盘文件无限堆积。</li>
 * </ol>
 *
 * <h3>集群行为</h3>
 * <p>用<b>按天唯一</b>的锁键 + 25 小时 TTL 保证「当天恰好有一个节点执行」，执行后不释放锁。
 * 这一条对本任务尤其重要：导出清理要删除磁盘上的导出文件，若 N 个节点并发执行，
 * 会出现「同一条记录被多次读取、多个节点同时删同一个文件」的竞争
 * （虽然 {@code Files.deleteIfExists} 本身幂等，但并发删除会放大 I/O 与锁等待）。</p>
 *
 * <p>策略选 <b>fail-open</b>：两项清理都是幂等删除（按时间条件删、按 status 删），
 * 重复执行无副作用。Redis 故障时宁可重复清理，也不要让日志表停止归档。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogRetentionJob {

    /** 导出任务/文件保留天数 */
    static final int EXPORT_RETENTION_DAYS = 7;

    /** 任务基础名（分布式锁键前缀） */
    static final String JOB_NAME = "log-retention";

    /** 日戳锁的存活时长：> 24 小时 */
    private static final Duration LOCK_TTL = Duration.ofHours(25);

    private final ApiCallLogMapper apiCallLogMapper;
    private final ExportTaskService exportTaskService;
    /** 集群任务互斥锁 */
    private final DistributedJobLock jobLock;

    /** 调用日志保留天数（配置文件 gatekeeper.log.retention-days） */
    @Value("${gatekeeper.log.retention-days:90}")
    private int retentionDays;

    /**
     * 每日 02:30 执行清理（避开业务高峰）
     */
    @Scheduled(cron = "0 30 2 * * ?")
    public void cleanup() {
        // 按天唯一的键 ⇒ 当天只有一个节点执行；执行后不释放，交给 TTL 过期
        if (!jobLock.tryLock(DistributedJobLock.daily(JOB_NAME), LOCK_TTL, true)) {
            log.debug("LogRetentionJob 今日已由其他节点执行，跳过");
            return;
        }
        cleanupCallLogs();
        cleanupExports();
    }

    /** 清理过期调用日志（fail-safe） */
    void cleanupCallLogs() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            QueryWrapper<ApiCallLog> wrapper = new QueryWrapper<>();
            wrapper.lt("request_time", cutoff);
            int deleted = apiCallLogMapper.delete(wrapper);
            log.info("Call log retention: deleted {} rows older than {} days", deleted, retentionDays);
        } catch (Exception e) {
            log.error("Call log retention cleanup failed", e);
        }
    }

    /** 清理过期导出任务与文件（fail-safe） */
    void cleanupExports() {
        try {
            int removed = exportTaskService.cleanupExpired(EXPORT_RETENTION_DAYS);
            log.info("Export cleanup: removed {} expired tasks", removed);
        } catch (Exception e) {
            log.error("Export cleanup failed", e);
        }
    }
}
