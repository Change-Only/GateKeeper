package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.ExportTask;
import com.gatekeeper.mapper.ExportTaskMapper;
import com.gatekeeper.service.ExportTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出任务服务单元测试（ExportTaskServiceImpl）
 *
 * <p>覆盖：任务创建（PENDING）、状态流转（RUNNING/SUCCESS/FAILED）、
 * 失败原因截断、分页查询、下载校验（状态+文件存在性）与路径穿越防护。</p>
 *
 * <p>另有专组用例覆盖<b>集群下启动回收的语义</b>：只回收「执行者已死」的孤儿任务，
 * 绝不误杀其他节点正在执行的任务（这是改造前的严重缺陷）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExportTaskServiceImplTest {

    @Mock
    private ExportTaskMapper exportTaskMapper;
    @Mock
    private ExportTaskLease exportTaskLease;

    @TempDir
    Path tempDir;

    private ExportTaskServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ExportTaskServiceImpl(exportTaskLease);
        injectMapper(service, exportTaskMapper);
        injectField(service, "exportDir", tempDir.toAbsolutePath().toString());
    }

    /** 反射注入 Mock Mapper（ServiceImpl 的 baseMapper 为 protected 字段） */
    private void injectMapper(Object target, Object mapper) throws Exception {
        Field field = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(target, mapper);
    }

    /** 反射注入 @Value 配置字段 */
    private void injectField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 造一个「创建于很久以前」的 RUNNING 任务。
     *
     * <p>宽限期过滤已下推到 SQL（由 DB 时钟判定），此处的 {@code createdAt} 仅用于
     * 让 mock 返回的实体在语义上自洽——代码本身不再读取它与应用时钟比较。</p>
     */
    private ExportTask staleRunning(Long id) {
        ExportTask t = new ExportTask();
        t.setId(id);
        t.setStatus(ExportTaskService.STATUS_RUNNING);
        t.setCreatedAt(LocalDateTime.now().minusHours(1));
        return t;
    }

    @Test
    void createTask_shouldInsertPendingTask() {
        service.createTask(ExportTaskService.TYPE_CALL_LOG, "admin");

        ArgumentCaptor<ExportTask> captor = ArgumentCaptor.forClass(ExportTask.class);
        verify(exportTaskMapper, times(1)).insert(captor.capture());
        ExportTask saved = captor.getValue();
        assertEquals(ExportTaskService.TYPE_CALL_LOG, saved.getType());
        assertEquals(ExportTaskService.STATUS_PENDING, saved.getStatus());
        assertEquals("admin", saved.getCreatedBy());
        assertEquals(0L, saved.getTotalRows());
    }

    @Test
    void startTask_shouldMovePendingToRunning() {
        ExportTask pending = new ExportTask();
        pending.setId(1L);
        pending.setStatus(ExportTaskService.STATUS_PENDING);
        when(exportTaskMapper.selectById(1L)).thenReturn(pending);

        ExportTask result = service.startTask(1L);

        assertNotNull(result);
        ArgumentCaptor<ExportTask> captor = ArgumentCaptor.forClass(ExportTask.class);
        verify(exportTaskMapper).updateById(captor.capture());
        assertEquals(ExportTaskService.STATUS_RUNNING, captor.getValue().getStatus());
    }

    @Test
    void startTask_shouldRejectNonPending() {
        ExportTask running = new ExportTask();
        running.setId(2L);
        running.setStatus(ExportTaskService.STATUS_RUNNING);
        when(exportTaskMapper.selectById(2L)).thenReturn(running);

        assertNull(service.startTask(2L), "非 PENDING 状态不应允许启动");
    }

    @Test
    void finishSuccess_shouldRecordFileAndRows() {
        ExportTask task = new ExportTask();
        task.setId(3L);
        when(exportTaskMapper.selectById(3L)).thenReturn(task);

        service.finishSuccess(3L, "call-log-20260829120000-3.csv", "call-log-20260829120000-3.csv", 12345L);

        ArgumentCaptor<ExportTask> captor = ArgumentCaptor.forClass(ExportTask.class);
        verify(exportTaskMapper).updateById(captor.capture());
        ExportTask updated = captor.getValue();
        assertEquals(ExportTaskService.STATUS_SUCCESS, updated.getStatus());
        assertEquals("call-log-20260829120000-3.csv", updated.getFileName());
        assertEquals(12345L, updated.getTotalRows());
        assertNotNull(updated.getFinishedAt());
    }

    @Test
    void finishFailed_shouldTruncateLongMessage() {
        ExportTask task = new ExportTask();
        task.setId(4L);
        when(exportTaskMapper.selectById(4L)).thenReturn(task);

        StringBuilder longMsg = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            longMsg.append('x');
        }
        service.finishFailed(4L, longMsg.toString());

        ArgumentCaptor<ExportTask> captor = ArgumentCaptor.forClass(ExportTask.class);
        verify(exportTaskMapper).updateById(captor.capture());
        ExportTask updated = captor.getValue();
        assertEquals(ExportTaskService.STATUS_FAILED, updated.getStatus());
        assertEquals(512, updated.getErrorMsg().length(), "失败原因应截断至 512 字符");
    }

    @Test
    void listTasks_shouldReturnPageResult() {
        ExportTask task = new ExportTask();
        task.setId(5L);
        when(exportTaskMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class), any(QueryWrapper.class)))
                .thenAnswer(invocation -> {
                    com.baomidou.mybatisplus.extension.plugins.pagination.Page<ExportTask> p = invocation.getArgument(0);
                    p.setRecords(Collections.singletonList(task));
                    p.setTotal(1);
                    return p;
                });

        PageResult<ExportTask> result = service.listTasks(1, 10, "admin");
        assertEquals(1, result.getTotal());
        assertEquals(5L, result.getRecords().get(0).getId());
    }

    @Test
    void getDownloadableTask_shouldAllowSuccessWithExistingFile() throws Exception {
        Path file = tempDir.resolve("ok.csv");
        Files.write(file, "日志ID\n1".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ExportTask task = new ExportTask();
        task.setId(6L);
        task.setStatus(ExportTaskService.STATUS_SUCCESS);
        task.setFilePath("ok.csv");
        task.setFileName("ok.csv");
        task.setCreatedBy("admin");
        when(exportTaskMapper.selectById(6L)).thenReturn(task);

        ExportTask result = service.getDownloadableTask(6L, "admin");
        assertNotNull(result);
    }

    @Test
    void getDownloadableTask_shouldRejectOtherCreator() throws Exception {
        Path file = tempDir.resolve("alice.csv");
        Files.write(file, "日志ID\n1".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ExportTask task = new ExportTask();
        task.setId(10L);
        task.setStatus(ExportTaskService.STATUS_SUCCESS);
        task.setFilePath("alice.csv");
        task.setFileName("alice.csv");
        task.setCreatedBy("alice");
        when(exportTaskMapper.selectById(10L)).thenReturn(task);

        assertNull(service.getDownloadableTask(10L, "bob"), "非本人任务不允许下载");
    }

    @Test
    void getDownloadableTask_shouldRejectSuccessWithoutFile() {
        ExportTask task = new ExportTask();
        task.setId(7L);
        task.setStatus(ExportTaskService.STATUS_SUCCESS);
        task.setFilePath("missing.csv");
        when(exportTaskMapper.selectById(7L)).thenReturn(task);

        assertNull(service.getDownloadableTask(7L, "admin"), "文件不存在时不应允许下载");
    }

    @Test
    void getDownloadableTask_shouldRejectFailedTask() {
        ExportTask task = new ExportTask();
        task.setId(8L);
        task.setStatus(ExportTaskService.STATUS_FAILED);
        task.setFilePath("whatever.csv");
        when(exportTaskMapper.selectById(8L)).thenReturn(task);

        assertNull(service.getDownloadableTask(8L, "admin"), "失败任务不应允许下载");
    }

    @Test
    void resolveDownloadPath_shouldBlockPathTraversal() {
        ExportTask task = new ExportTask();
        task.setId(9L);
        task.setFilePath("../../etc/passwd");

        assertNull(service.resolveDownloadPath(task), "路径穿越（..）必须被拒绝");
    }

    @Test
    void cleanupExpired_shouldDeleteFilesAndTaskRecords() throws Exception {
        Path file = tempDir.resolve("expired.csv");
        Files.write(file, "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ExportTask task = new ExportTask();
        task.setId(99L);
        task.setStatus(ExportTaskService.STATUS_SUCCESS);
        task.setFilePath("expired.csv");
        task.setFinishedAt(java.time.LocalDateTime.now().minusDays(10));

        when(exportTaskMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(task));

        int removed = service.cleanupExpired(7);

        assertEquals(1, removed);
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(file), "过期导出文件应被删除");
        verify(exportTaskMapper).delete(any(QueryWrapper.class));
    }

    // ============================================================
    // 集群：启动回收语义
    // ============================================================

    @Test
    @DisplayName("启动回收：租约不存在（执行者已死）→ 置 FAILED")
    void failStaleTasks_reclaimsOrphanWhenLeaseAbsent() {
        when(exportTaskMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(staleRunning(11L)));
        when(exportTaskLease.isHeld(11L)).thenReturn(Boolean.FALSE);

        service.failStaleTasksOnBoot();

        ArgumentCaptor<ExportTask> captor = ArgumentCaptor.forClass(ExportTask.class);
        verify(exportTaskMapper).updateById(captor.capture());
        assertEquals(ExportTaskService.STATUS_FAILED, captor.getValue().getStatus());
        assertEquals(11L, captor.getValue().getId());
        assertNotNull(captor.getValue().getFinishedAt());
    }

    @Test
    @DisplayName("启动回收：租约仍在（其他节点正在跑）→ 绝不置 FAILED")
    void failStaleTasks_doesNotKillTaskRunningOnAnotherNode() {
        when(exportTaskMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(staleRunning(12L)));
        when(exportTaskLease.isHeld(12L)).thenReturn(Boolean.TRUE);

        service.failStaleTasksOnBoot();

        verify(exportTaskMapper, never()).updateById(any(ExportTask.class));
    }

    @Test
    @DisplayName("启动回收：租约查不到（Redis 不可用）→ fail-safe，一台都不回收")
    void failStaleTasks_abortsEntirelyWhenLeaseUnknown() {
        when(exportTaskMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Arrays.asList(staleRunning(13L), staleRunning(14L)));
        when(exportTaskLease.isHeld(anyLong())).thenReturn(null);

        service.failStaleTasksOnBoot();

        verify(exportTaskMapper, never()).updateById(any(ExportTask.class));
    }

    @Test
    @DisplayName("启动回收：宽限期过滤必须下推到 SQL，且两端都用 DB 时钟")
    void failStaleTasks_appliesGraceFilterInSqlWithDbClock() {
        ArgumentCaptor<QueryWrapper<ExportTask>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        when(exportTaskMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        service.failStaleTasksOnBoot();

        verify(exportTaskMapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        // 可证伪点：改造前此处只有 status 条件，宽限期靠应用侧
        // `createdAt.isAfter(LocalDateTime.now().minusMinutes(2))` 判断 —— 当 JVM 与
        // 库的时区不一致时该不等式恒真，回收整体失效（实测 8 小时偏差）。
        assertTrue(sql.contains("created_at"), "SQL 应含宽限期比较列 created_at: " + sql);
        assertTrue(sql.contains("DATE_SUB"), "宽限期应在 SQL 内做时间减法: " + sql);
        assertTrue(sql.contains("NOW()"), "比较基准必须是 DB 的 NOW()（库时钟），不得是应用时钟: " + sql);
    }

    @Test
    @DisplayName("启动回收：无 RUNNING 任务 → 不产生任何查询/更新")
    void failStaleTasks_noopWhenNothingRunning() {
        when(exportTaskMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        service.failStaleTasksOnBoot();

        verify(exportTaskMapper, never()).updateById(any(ExportTask.class));
    }

    @Test
    @DisplayName("启动回收：查询本身抛异常也不阻断应用启动")
    void failStaleTasks_swallowsQueryError() {
        when(exportTaskMapper.selectList(any(QueryWrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.failStaleTasksOnBoot());
    }

    @Test
    @DisplayName("启动回收：多个孤儿中只有租约缺失的被回收")
    void failStaleTasks_mixedLeaseStates() {
        when(exportTaskMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Arrays.asList(staleRunning(21L), staleRunning(22L)));
        when(exportTaskLease.isHeld(21L)).thenReturn(Boolean.FALSE);
        when(exportTaskLease.isHeld(22L)).thenReturn(Boolean.TRUE);

        service.failStaleTasksOnBoot();

        ArgumentCaptor<ExportTask> captor = ArgumentCaptor.forClass(ExportTask.class);
        verify(exportTaskMapper, times(1)).updateById(captor.capture());
        assertEquals(21L, captor.getValue().getId(), "只应回收租约缺失的那个");
    }
}
