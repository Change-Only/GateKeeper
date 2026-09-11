package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.ExportTask;
import com.gatekeeper.mapper.ExportTaskMapper;
import com.gatekeeper.service.ExportTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出任务服务实现 — 异步下载中心任务的生命周期管理
 *
 * <p>文件安全约定：数据库只存<b>相对文件名</b>，下载时以导出目录为根解析并做
 * normalize 前缀校验，杜绝 {@code ../} 路径穿越读取任意文件。</p>
 */
@Slf4j
@Service
public class ExportTaskServiceImpl extends ServiceImpl<ExportTaskMapper, ExportTask> implements ExportTaskService {

    /** 导出文件根目录（支持环境变量 GATEKEEPER_EXPORT_DIR 覆盖，Docker 挂载持久卷） */
    @Value("${gatekeeper.export.dir:./data/exports}")
    private String exportDir;

    /** 失败原因长度上限（与表字段 error_msg VARCHAR(512) 对齐） */
    private static final int MAX_ERROR_MSG_LEN = 512;

    /**
     * 启动自愈：将遗留的 RUNNING 任务标记为失败
     *
     * <p>导出任务在应用进程内执行，服务重启后 RUNNING 任务永远无人继续执行，
     * 会永久卡在"生成中"。启动时将这类任务重置为 FAILED，前端轮询后可感知并重建。</p>
     */
    @PostConstruct
    public void failStaleTasksOnBoot() {
        QueryWrapper<ExportTask> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_RUNNING);
        ExportTask patch = new ExportTask();
        patch.setStatus(STATUS_FAILED);
        patch.setErrorMsg("服务重启，任务中断，请重新创建");
        patch.setFinishedAt(LocalDateTime.now());
        update(patch, wrapper);
    }

    @Override
    public Long createTask(String type, String createdBy) {
        ExportTask task = new ExportTask();
        task.setType(type);
        task.setStatus(STATUS_PENDING);
        task.setCreatedBy(createdBy);
        task.setTotalRows(0L);
        save(task);
        return task.getId();
    }

    @Override
    public ExportTask startTask(Long id) {
        ExportTask task = getById(id);
        if (task == null || !STATUS_PENDING.equals(task.getStatus())) {
            return null;
        }
        task.setStatus(STATUS_RUNNING);
        updateById(task);
        return task;
    }

    @Override
    public void finishSuccess(Long id, String fileName, String filePath, long totalRows) {
        ExportTask task = getById(id);
        if (task == null) {
            return;
        }
        task.setStatus(STATUS_SUCCESS);
        task.setFileName(fileName);
        task.setFilePath(filePath);
        task.setTotalRows(totalRows);
        task.setFinishedAt(LocalDateTime.now());
        updateById(task);
    }

    @Override
    public void finishFailed(Long id, String errorMsg) {
        ExportTask task = getById(id);
        if (task == null) {
            return;
        }
        task.setStatus(STATUS_FAILED);
        String msg = errorMsg == null ? "未知错误" : errorMsg;
        task.setErrorMsg(msg.length() > MAX_ERROR_MSG_LEN ? msg.substring(0, MAX_ERROR_MSG_LEN) : msg);
        task.setFinishedAt(LocalDateTime.now());
        updateById(task);
    }

    @Override
    public PageResult<ExportTask> listTasks(int current, int size, String createdBy) {
        Page<ExportTask> page = new Page<>(current, size);
        QueryWrapper<ExportTask> wrapper = new QueryWrapper<>();
        if (createdBy != null && !createdBy.isEmpty()) {
            wrapper.eq("created_by", createdBy);
        }
        wrapper.orderByDesc("created_at");
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public ExportTask getDownloadableTask(Long id, String creator) {
        ExportTask task = getById(id);
        if (task == null || !STATUS_SUCCESS.equals(task.getStatus())) {
            return null;
        }
        // 归属校验：任务记有创建人时，仅允许本人下载（防越权枚举 taskId）
        if (task.getCreatedBy() != null && !task.getCreatedBy().isEmpty()
                && !task.getCreatedBy().equals(creator)) {
            return null;
        }
        Path file = resolveDownloadPath(task);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        return task;
    }

    @Override
    public Path resolveDownloadPath(ExportTask task) {
        if (task == null || task.getFilePath() == null || task.getFilePath().isEmpty()) {
            return null;
        }
        Path dir = Paths.get(exportDir).toAbsolutePath().normalize();
        Path file = dir.resolve(task.getFilePath()).normalize();
        // 防路径穿越：解析结果必须仍位于导出目录内
        if (!file.startsWith(dir)) {
            return null;
        }
        return file;
    }

    @Override
    public int cleanupExpired(int days) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        QueryWrapper<ExportTask> wrapper = new QueryWrapper<>();
        wrapper.in("status", STATUS_SUCCESS, STATUS_FAILED)
                .lt("finished_at", cutoff);
        List<ExportTask> expired = list(wrapper);
        for (ExportTask task : expired) {
            Path file = resolveDownloadPath(task);
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (java.io.IOException e) {
                    log.warn("Failed to delete expired export file: {}", file);
                }
            }
        }
        int removed = expired.size();
        if (removed > 0) {
            remove(wrapper);
        }
        log.info("Export task cleanup: removed {} expired tasks (older than {} days)", removed, days);
        return removed;
    }
}
