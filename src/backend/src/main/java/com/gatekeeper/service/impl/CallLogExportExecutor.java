package com.gatekeeper.service.impl;

import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.LogExportQuery;
import com.gatekeeper.entity.ApiCallLog;
import com.gatekeeper.entity.ExportTask;
import com.gatekeeper.service.CallLogService;
import com.gatekeeper.service.ExportTaskService;
import com.gatekeeper.util.CsvUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 调用日志导出执行器 — 在异步线程池中分批流式生成 CSV
 *
 * <p>背景：日志量达百万级时，同步导出会一次性把全量数据拉进内存
 * （单条日志含完整请求/响应报文可达 8KB+，百万条 ≈ 8GB），足以打挂与网关
 * 同 JVM 的管理服务。本执行器改为：</p>
 * <ol>
 *   <li>分批查询（{@link #BATCH_SIZE} 条/批），内存峰值恒定在单批规模；</li>
 *   <li>边查边写<b>临时文件</b>（{@code {taskId}.csv.tmp}），全部写完再原子改名，
 *       避免前端轮询时下载到写了一半的文件；</li>
 *   <li>任何异常都不抛出到线程池外：捕获后标记任务 FAILED 并记录原因。</li>
 * </ol>
 *
 * <p>复用全局异步线程池（有界队列 + CallerRuns 背压）：队列满时任务由调用线程执行，
 * 保证导出任务不会无限堆积。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallLogExportExecutor {

    /** 每批拉取条数：内存峰值 ≈ 单批记录数 × 单条大小 */
    private static final int BATCH_SIZE = 5000;

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CallLogService callLogService;
    private final ExportTaskService exportTaskService;
    /** 执行租约：让「本任务是否还有节点在跑」在集群内可判定（详见 {@link ExportTaskLease}） */
    private final ExportTaskLease exportTaskLease;

    /** 导出文件根目录（与 ExportTaskServiceImpl 同源配置） */
    @Value("${gatekeeper.export.dir:./data/exports}")
    private String exportDir;

    /**
     * 异步执行导出任务：RUNNING → 分批写临时 CSV → 改名 → SUCCESS/FAILED
     *
     * @param taskId 导出任务 ID
     * @param query  创建任务时固化的筛选条件
     */
    @Async
    public void run(Long taskId, LogExportQuery query) {
        // 先写租约、再置 RUNNING：保证「状态 = RUNNING」的任务一定有租约可查，
        // 否则启动回收方可能看到一个「RUNNING 但无租约」的任务而误判为孤儿。
        exportTaskLease.hold(taskId);

        ExportTask task = exportTaskService.startTask(taskId);
        if (task == null) {
            // 未能启动（任务不存在 / 已被启动）：撤掉刚占下的租约，避免留下假心跳
            exportTaskLease.release(taskId);
            log.warn("Export task {} cannot start (not found or already started)", taskId);
            return;
        }

        Path tmpFile = null;
        long rows = 0;
        try {
            Path dir = Paths.get(exportDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            tmpFile = dir.resolve(taskId + ".csv.tmp");

            // 边查边写：每批最多 BATCH_SIZE 条，内存恒定
            try (BufferedWriter writer = Files.newBufferedWriter(tmpFile, java.nio.charset.StandardCharsets.UTF_8)) {
                writer.write(CsvUtil.BOM);
                writer.write(CsvUtil.HEADER);
                writer.write("\n");
                int pageNo = 1;
                while (true) {
                    PageResult<ApiCallLog> pr = callLogService.pageQuery(pageNo, BATCH_SIZE,
                            query.getAppId(), query.getInterfaceId(), query.getResponseStatus(),
                            query.getClientIp(), query.getIsRateLimited(), query.getIsBlocked(),
                            query.getStartTime(), query.getEndTime());
                    List<ApiCallLog> records = pr.getRecords();
                    if (records == null || records.isEmpty()) {
                        break;
                    }
                    for (ApiCallLog row : records) {
                        writer.write(CsvUtil.line(row));
                        rows++;
                    }
                    // 每批续租：维持「本任务有人在跑」的心跳，避免长时间导出被启动回收误判为孤儿
                    exportTaskLease.renew(taskId);
                    if (records.size() < BATCH_SIZE) {
                        break; // 最后一批
                    }
                    pageNo++;
                }
            }

            // 原子改名：前端轮询到 SUCCESS 时文件必然完整
            String fileName = "call-log-" + LocalDateTime.now().format(FILE_TIME) + "-" + taskId + ".csv";
            Path finalFile = dir.resolve(fileName);
            Files.move(tmpFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            exportTaskService.finishSuccess(taskId, fileName, fileName, rows);
            log.info("Export task {} finished: rows={}, file={}", taskId, rows, fileName);
        } catch (Exception e) {
            log.error("Export task {} failed", taskId, e);
            cleanup(tmpFile);
            exportTaskService.finishFailed(taskId, e.getMessage());
        } finally {
            // 无论成功失败都交还租约：任务已被终结（SUCCESS/FAILED），不应再有「执行中」的心跳。
            // 若此处因进程崩溃未执行到，租约也会在 TTL 到期后自动失效，由下次启动回收。
            exportTaskLease.release(taskId);
        }
    }

    /** 清理残留临时文件（失败路径），忽略清理本身异常 */
    private void cleanup(Path tmpFile) {
        if (tmpFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tmpFile);
        } catch (Exception ignore) {
            log.warn("Failed to clean temp export file: {}", tmpFile);
        }
    }
}
