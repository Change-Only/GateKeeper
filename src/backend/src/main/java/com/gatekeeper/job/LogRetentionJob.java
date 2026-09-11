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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogRetentionJob {

    /** 导出任务/文件保留天数 */
    static final int EXPORT_RETENTION_DAYS = 7;

    private final ApiCallLogMapper apiCallLogMapper;
    private final ExportTaskService exportTaskService;

    /** 调用日志保留天数（配置文件 gatekeeper.log.retention-days） */
    @Value("${gatekeeper.log.retention-days:90}")
    private int retentionDays;

    /**
     * 每日 02:30 执行清理（避开业务高峰）
     */
    @Scheduled(cron = "0 30 2 * * ?")
    public void cleanup() {
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
