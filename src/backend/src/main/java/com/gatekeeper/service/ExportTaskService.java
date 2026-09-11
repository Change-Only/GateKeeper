package com.gatekeeper.service;

import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.ExportTask;

import java.nio.file.Path;

/**
 * 导出任务服务 — 异步下载中心的任务生命周期管理
 *
 * <p>职责：任务的创建、状态流转（PENDING→RUNNING→SUCCESS/FAILED）、
 * 分页查询与下载文件解析（含路径穿越校验）。实际的 CSV 生成在
 * {@code CallLogExportExecutor}（@Async）中执行。</p>
 */
public interface ExportTaskService {

    /** 任务状态：排队中 */
    String STATUS_PENDING = "PENDING";
    /** 任务状态：执行中 */
    String STATUS_RUNNING = "RUNNING";
    /** 任务状态：成功 */
    String STATUS_SUCCESS = "SUCCESS";
    /** 任务状态：失败 */
    String STATUS_FAILED = "FAILED";

    /** 导出类型：调用日志 */
    String TYPE_CALL_LOG = "CALL_LOG";

    /**
     * 创建导出任务（PENDING），返回任务 ID
     *
     * @param type      导出类型（{@link #TYPE_CALL_LOG}）
     * @param createdBy 创建人登录名（可为空）
     * @return 新任务 ID
     */
    Long createTask(String type, String createdBy);

    /**
     * 将任务从 PENDING 置为 RUNNING；任务不存在或状态不符时返回 null
     *
     * @param id 任务 ID
     * @return 更新后的任务，条件不满足返回 null
     */
    ExportTask startTask(Long id);

    /**
     * 标记任务成功：写入文件名、相对路径与导出行数
     */
    void finishSuccess(Long id, String fileName, String filePath, long totalRows);

    /**
     * 标记任务失败：写入失败原因（截断至 512 字符）
     */
    void finishFailed(Long id, String errorMsg);

    /**
     * 分页查询导出任务（按创建时间倒序）
     *
     * @param createdBy 创建人（可选，null 查全部）
     */
    PageResult<ExportTask> listTasks(int current, int size, String createdBy);

    /**
     * 校验并返回可下载任务：要求状态 SUCCESS、文件真实存在，
     * 且当前用户为该任务的创建人（防越权下载他人导出文件）
     *
     * @param id      任务 ID
     * @param creator 当前登录用户名（来自 JWT 拦截器注入的请求属性）
     * @return 任务实体；状态不符、文件不存在或非本人任务时返回 null
     */
    ExportTask getDownloadableTask(Long id, String creator);

    /**
     * 将任务记录中的相对路径解析为磁盘绝对路径（防穿越校验）
     *
     * @param task 已校验可下载的任务
     * @return 文件路径；路径穿越（含 ..）或越出导出目录时返回 null
     */
    Path resolveDownloadPath(ExportTask task);

    /**
     * 清理过期导出任务：删除已完成（SUCCESS/FAILED）超过 {@code days} 天的
     * 磁盘文件与任务记录，由定时任务每日调用，防止导出文件无限堆积
     *
     * @param days 保留天数
     * @return 清理的任务记录数
     */
    int cleanupExpired(int days);
}
