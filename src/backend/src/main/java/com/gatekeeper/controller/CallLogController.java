package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.dto.LogExportQuery;
import com.gatekeeper.entity.ApiCallLog;
import com.gatekeeper.entity.ExportTask;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.CallLogService;
import com.gatekeeper.service.ExportTaskService;
import com.gatekeeper.service.impl.CallLogExportExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 调用日志 Controller
 *
 * <p>负责 API 调用日志（ApiCallLog）的查询与<b>异步导出</b>，接口前缀 {@code /log}：</p>
 * <ul>
 *   <li>GET /log/list                 分页查询（多条件筛选）</li>
 *   <li>GET /log/{id}                 单条详情</li>
 *   <li>POST /log/export              创建导出任务（异步，返回任务 ID）</li>
 *   <li>GET /log/export/tasks         查询导出任务列表（按创建人）</li>
 *   <li>GET /log/export/{id}/download 下载导出文件（任务 SUCCESS 才可下载）</li>
 * </ul>
 *
 * <p>导出为异步任务模式的原因：日志量达百万级时同步导出会一次性把全量数据
 * 拉进内存，拖垮与网关同 JVM 的管理服务；改为后台分批流式生成 CSV，前端轮询
 * 任务状态后下载（详见 {@link CallLogExportExecutor}）。</p>
 */
@RestController
@RequestMapping("/log")
@RequiredArgsConstructor
@Tag(name = "调用日志", description = "调用日志管理接口")
public class CallLogController {

    private final CallLogService callLogService;
    private final ExportTaskService exportTaskService;
    private final CallLogExportExecutor exportExecutor;

    /**
     * 分页查询调用日志
     */
    @Operation(summary = "分页查询调用日志列表")
    @GetMapping("/list")
    public Result<PageResult<ApiCallLog>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) Long interfaceId,
            @RequestParam(required = false) Integer responseStatus,
            @RequestParam(required = false) String clientIp,
            @RequestParam(required = false) Boolean isRateLimited,
            @RequestParam(required = false) Boolean isBlocked,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return Result.success(callLogService.pageQuery(current, size, appId, interfaceId,
                responseStatus, clientIp, isRateLimited, isBlocked, startTime, endTime));
    }

    /**
     * 查询单条调用日志详情
     */
    @Operation(summary = "查询调用日志详情")
    @GetMapping("/{id}")
    public Result<ApiCallLog> detail(@PathVariable Long id) {
        return Result.success(callLogService.getDetail(id));
    }

    /**
     * 创建导出任务（异步）：固化当前筛选条件，立即返回任务 ID
     *
     * <p>筛选参数与 /log/list 一致。任务在后台分批生成 CSV，
     * 前端通过 /log/export/tasks 轮询状态，SUCCESS 后走 download 下载。</p>
     *
     * @param username 当前登录用户名（由 JWT 拦截器注入请求属性）
     */
    @RequirePerm(value = "audit:export", risk = true)
    @Operation(summary = "创建导出任务")
    @PostMapping("/export")
    public Result<Long> createExport(
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) Long interfaceId,
            @RequestParam(required = false) Integer responseStatus,
            @RequestParam(required = false) String clientIp,
            @RequestParam(required = false) Boolean isRateLimited,
            @RequestParam(required = false) Boolean isBlocked,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestAttribute(value = "X-USERNAME", required = false) String username) {
        LogExportQuery query = new LogExportQuery();
        query.setAppId(appId);
        query.setInterfaceId(interfaceId);
        query.setResponseStatus(responseStatus);
        query.setClientIp(clientIp);
        query.setIsRateLimited(isRateLimited);
        query.setIsBlocked(isBlocked);
        query.setStartTime(startTime);
        query.setEndTime(endTime);

        Long taskId = exportTaskService.createTask(ExportTaskService.TYPE_CALL_LOG, username);
        exportExecutor.run(taskId, query);
        return Result.success(taskId);
    }

    /**
     * 查询导出任务列表（当前登录人创建的任务，按创建时间倒序）
     */
    @Operation(summary = "查询导出任务列表")
    @GetMapping("/export/tasks")
    public Result<PageResult<ExportTask>> tasks(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestAttribute(value = "X-USERNAME", required = false) String username) {
        return Result.success(exportTaskService.listTasks(current, size, username));
    }

    /**
     * 下载导出文件：仅任务 SUCCESS、文件存在且为本人创建时允许，流式返回不占内存
     */
    @Operation(summary = "下载导出文件")
    @GetMapping("/export/{taskId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long taskId,
                                             @RequestAttribute(value = "X-USERNAME", required = false) String username) {
        ExportTask task = exportTaskService.getDownloadableTask(taskId, username);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        Path file = exportTaskService.resolveDownloadPath(task);
        Resource resource = new FileSystemResource(file.toFile());
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment;filename=\"" + task.getFileName() + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(file.toFile().length())
                .body(resource);
    }
}
