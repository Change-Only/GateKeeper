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
     * 启动回收的宽限期（分钟）：创建时间在此窗口内的 RUNNING 任务不参与回收。
     *
     * <p>覆盖「已置 RUNNING、执行租约尚未写入」的短窗口，避免误杀刚起步的任务。</p>
     */
    static final long BOOT_GRACE_MINUTES = 2L;

    /** 执行租约：用于判定 RUNNING 任务是否真的还有节点在执行（集群语义的核心） */
    private final ExportTaskLease exportTaskLease;

    /**
     * 构造注入执行租约。
     *
     * <p>{@link ServiceImpl} 本身有无参构造，此处显式声明带参构造以便单测直接注入 mock。</p>
     *
     * @param exportTaskLease 导出任务执行租约
     */
    public ExportTaskServiceImpl(ExportTaskLease exportTaskLease) {
        this.exportTaskLease = exportTaskLease;
    }

    /**
     * 启动自愈：回收「执行者已死」的遗留 RUNNING 任务。
     *
     * <p>导出任务在应用进程内执行，进程退出后 RUNNING 任务永远无人继续执行，
     * 会永久卡在「生成中」。启动时把这类任务置为 FAILED，前端轮询后可感知并重建。</p>
     *
     * <h3>集群语义（改造要点）</h3>
     * <p>改造前是「启动时把所有 RUNNING 一律置 FAILED」。<b>这在集群下是错的</b>：
     * 节点 B 重启时会把节点 A 正在正常执行的任务判死，用户既拿不到结果、
     * 也下不到 A 其实已经生成好的文件。</p>
     *
     * <p>现在改为按 {@link ExportTaskLease 执行租约} 逐个判定，三条规则：</p>
     * <ol>
     *   <li>租约<b>存在</b> ⇒ 有节点在执行（可能不是本机）⇒ <b>跳过</b>；</li>
     *   <li>租约<b>不存在</b> ⇒ 执行者已死 ⇒ 置 FAILED 回收；</li>
     *   <li>租约<b>查不到</b>（Redis 不可用）⇒ <b>立即中止整个回收</b>，一台都不动
     *       —— 无法区分「没人在跑」和「问不到」，宁可留僵尸也不误杀。</li>
     * </ol>
     *
     * <p>另设宽限期 {@value #BOOT_GRACE_MINUTES} 分钟：刚创建的任务可能还处在
     * 「已置 RUNNING、租约尚未写入」的窗口内，不参与本次回收。该过滤在 SQL 里
     * 用 DB 时钟比较，避免依赖「应用时钟与库时钟一致」这一隐含前提。</p>
     */
    @PostConstruct
    public void failStaleTasksOnBoot() {
        try {
            QueryWrapper<ExportTask> wrapper = new QueryWrapper<>();
            // 🔴 宽限期的比较【必须由 DB 完成】：created_at 由 DDL 的 DEFAULT CURRENT_TIMESTAMP
            //   生成（即「库时钟」），若基准取应用进程的 LocalDateTime.now()（「JVM 时钟」），
            //   两者容器时区不一致时该比较会恒为真 —— 实测部署环境中 MySQL 为
            //   TZ=Asia/Shanghai、backend 未设 TZ 而取 UTC，相差 8 小时 ⇒ 回收逻辑整体失效、
            //   孤儿任务永远卡在 RUNNING。下推到 SQL 后不等式两端都取自 DB 时钟，
            //   与应用/DB 的时区配置是否一致无关（{0} 由 MyBatis 参数绑定，无注入风险）。
            wrapper.eq("status", STATUS_RUNNING)
                    .apply("created_at < DATE_SUB(NOW(), INTERVAL {0} MINUTE)", BOOT_GRACE_MINUTES);
            List<ExportTask> running = list(wrapper);
            if (running == null || running.isEmpty()) {
                return;
            }

            int reclaimed = 0;
            int skipped = 0;
            for (ExportTask task : running) {
                if (task == null || task.getId() == null) {
                    continue;
                }
                Boolean held = exportTaskLease.isHeld(task.getId());
                if (held == null) {
                    // Redis 不可用 ⇒ 无法判定 ⇒ fail-safe：本次不回收任何任务
                    log.error("启动回收导出任务中止：无法查询执行租约（Redis 不可用），"
                            + "为避免误杀正在执行的任务，本次不回收任何 RUNNING 任务");
                    return;
                }
                if (Boolean.TRUE.equals(held)) {
                    skipped++;   // 有节点正在执行 ⇒ 不动它
                    continue;
                }
                ExportTask patch = new ExportTask();
                patch.setId(task.getId());
                patch.setStatus(STATUS_FAILED);
                patch.setErrorMsg("执行节点已退出，任务中断，请重新创建");
                patch.setFinishedAt(LocalDateTime.now());
                updateById(patch);
                reclaimed++;
            }
            if (reclaimed > 0 || skipped > 0) {
                log.info("启动回收导出任务：回收孤儿 {} 个，跳过 {} 个（宽限期内 / 有节点在执行）",
                        reclaimed, skipped);
            }
        } catch (Exception e) {
            // 回收失败不得阻断应用启动
            log.error("启动回收导出任务失败（不影响应用启动）", e);
        }
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
