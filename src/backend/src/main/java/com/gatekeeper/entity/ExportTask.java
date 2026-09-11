package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导出任务表（export_task）— 异步下载中心任务数据
 *
 * <p>日志量达百万级时，同步导出（一次性拉全量进内存再写响应流）会拖垮与网关同 JVM
 * 的管理服务。因此导出改为<b>异步任务</b>模式：</p>
 * <ol>
 *   <li>创建任务：{@code PENDING}；</li>
 *   <li>后台线程分批（5000 条/批）流式写临时 CSV，标记 {@code RUNNING}；</li>
 *   <li>写完改名并落盘，标记 {@code SUCCESS}（记录文件名/行数）；失败记 {@code FAILED} + 原因；</li>
 *   <li>前端轮询任务列表，完成后提供下载链接。</li>
 * </ol>
 *
 * <p>文件路径只存<b>相对路径</b>，下载时由服务端拼接导出目录并做路径穿越校验，
 * 前端无法指定任意磁盘路径。</p>
 */
@Data
@TableName("export_task")
public class ExportTask {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 导出类型：CALL_LOG=调用日志 */
    private String type;

    /** 任务状态：PENDING=排队中 / RUNNING=执行中 / SUCCESS=成功 / FAILED=失败 */
    private String status;

    /** 下载文件名（如 call-log-20260829120000-8.csv） */
    private String fileName;

    /** 服务器文件相对路径（相对导出目录，不含目录分隔符前缀） */
    private String filePath;

    /** 导出行数（不含表头） */
    private Long totalRows;

    /** 失败原因（FAILED 时有值，截断至 512 字符） */
    private String errorMsg;

    /** 创建人（登录用户名，冗余便于按人查询） */
    private String createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 完成时间（成功或失败时写入） */
    private LocalDateTime finishedAt;
}
