package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.Alert;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 告警中心 Controller
 *
 * <p>为管理端提供运营告警的聚合接口，业务模块包括：告警分页查询、未读统计、
 * 标记已读 / 全部已读、告警处置（已处理 / 已忽略）。</p>
 *
 * <p>主要接口路径前缀：{@code /alert}
 * <ul>
 *   <li>GET  /alert/list          分页查询告警（支持等级/来源/状态筛选）</li>
 *   <li>GET  /alert/unread-count  未读告警数量（供顶栏铃铛轮询）</li>
 *   <li>PUT  /alert/{id}/read     标记单条已读</li>
 *   <li>PUT  /alert/read-all      一键全部已读</li>
 *   <li>PUT  /alert/{id}/handle   处置告警（已处理/已忽略）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/alert")
@RequiredArgsConstructor
@Tag(name = "告警", description = "告警管理接口")
public class AlertController {

    private final AlertService alertService;

    /**
     * 分页查询告警列表
     *
     * @param current 当前页码（默认第 1 页）
     * @param size    每页条数（默认 10 条）
     * @param level   告警等级（可选，INFO/WARNING/CRITICAL）
     * @param source  告警来源（可选，GATEWAY/SECURITY/RATE_LIMIT/SYSTEM）
     * @param status  处理状态（可选，0=未读）
     * @return 告警分页结果
     */
    @Operation(summary = "分页查询告警列表")
    @GetMapping("/list")
    public Result<PageResult<Alert>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer status) {
        return Result.success(alertService.pageQuery(current, size, level, source, status));
    }

    /**
     * 查询未读告警数量（供顶栏铃铛轮询展示红点）
     *
     * @return 未读告警数
     */
    @Operation(summary = "查询未读告警数")
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.success(alertService.unreadCount());
    }

    /**
     * 标记单条告警为已读
     *
     * @param id 告警 ID
     * @return 操作结果
     */
    @Operation(summary = "标记告警已读")
    @PutMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        alertService.markRead(id);
        return Result.success();
    }

    /**
     * 一键全部标记已读
     *
     * @return 操作结果
     */
    @Operation(summary = "全部标记已读")
    @PutMapping("/read-all")
    public Result<Void> markAllRead() {
        alertService.markAllRead();
        return Result.success();
    }

    /**
     * 处置告警：标记为已处理（status=2）或已忽略（status=3）
     *
     * @param id     告警 ID
     * @param status 目标状态（2=已处理, 3=已忽略）
     * @param remark 处理备注（可选）
     * @return 操作结果
     */
    @Operation(summary = "处置告警")
    @RequirePerm(value = "alarm:handle", risk = false)
    @PutMapping("/{id}/handle")
    public Result<Void> handle(@PathVariable Long id,
                               @RequestParam Integer status,
                               @RequestParam(required = false) String remark) {
        alertService.handleAlert(id, status, remark);
        return Result.success();
    }
}
