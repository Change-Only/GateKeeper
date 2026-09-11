package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.IpBan;
import com.gatekeeper.entity.SecurityEvent;
import com.gatekeeper.entity.SecurityRule;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.security.banner.IpBanService;
import com.gatekeeper.service.SecurityEventService;
import com.gatekeeper.service.SecurityRuleService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 安全防护 Controller
 *
 * <p>负责网关安全防护相关的管理，业务模块包括三部分：
 * IP 封禁管理（封禁/解封）、安全事件查询与处理、安全检测规则的配置管理。</p>
 *
 * <p>T02 收尾：{@code /security/rule} 的 POST/PUT/DELETE 已打上
 * {@code sys:security:update} 高危权限点。</p>
 *
 * <p>主要接口路径前缀：{@code /security}
 * <ul>
 *   <li>GET/POST/PUT /security/ip-ban*     IP 封禁列表/封禁/解封</li>
 *   <li>GET/PUT /security/event*           安全事件列表/处理</li>
 *   <li>GET/POST/PUT/DELETE /security/rule* 安全检测规则管理（POST/PUT/DELETE 高危）</li>
 * </ul>
 */
@RestController
@RequestMapping("/security")
@RequiredArgsConstructor
@Tag(name = "安全", description = "安全管理接口")
public class SecurityController {

    private final IpBanService ipBanService;
    private final SecurityEventService securityEventService;
    private final SecurityRuleService securityRuleService;

    // === IP 封禁管理 ===

    /**
     * 分页查询 IP 封禁列表
     *
     * @param current 当前页码（默认第 1 页）
     * @param size    每页条数（默认 10 条）
     * @return 分页结果，包含封禁记录列表及总数
     */
    @Operation(summary = "分页查询封禁列表")
    @GetMapping("/ip-ban/list")
    public Result<PageResult<IpBan>> banList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(ipBanService.pageBans(current, size));
    }

    /**
     * 封禁指定 IP
     *
     * @param request 封禁请求（含 IP 地址、关联应用、封禁原因、封禁时长等）
     * @return 操作结果（无业务数据返回）
     */
    @RequirePerm(value = "block_rule:manual", risk = true)
    @Operation(summary = "手动封禁IP")
    @PostMapping("/ip-ban")
    public Result<Void> banIp(@RequestBody BanRequest request) {
        ipBanService.banIp(request.getIpAddress(), request.getAppId(),
                request.getReason(), request.getDurationMin());
        return Result.success();
    }

    /**
     * 解封指定封禁记录
     *
     * @param id 封禁记录 ID
     * @return 操作结果（无业务数据返回）
     */
    @RequirePerm(value = "block_rule:manual", risk = true)
    @Operation(summary = "解封IP")
    @PutMapping("/ip-ban/{id}/unban")
    public Result<Void> unbanIp(@PathVariable Long id) {
        ipBanService.unbanIp(id);
        return Result.success();
    }

    // === 安全事件 ===

    /**
     * 分页查询安全事件列表
     *
     * @param current      当前页码（默认第 1 页）
     * @param size         每页条数（默认 10 条）
     * @param eventType    事件类型（可选，按类型筛选）
     * @param handleStatus 处理状态（可选，如待处理/已处理）
     * @return 分页结果，包含安全事件列表及总数
     */
    @Operation(summary = "分页查询安全事件列表")
    @GetMapping("/event/list")
    public Result<PageResult<SecurityEvent>> eventList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Integer handleStatus) {
        return Result.success(securityEventService.pageQuery(current, size, eventType, handleStatus));
    }

    /**
     * 处理安全事件
     *
     * @param id     安全事件 ID
     * @param status 处理后的状态
     * @param remark 处理备注（可选）
     * @return 操作结果（无业务数据返回）
     */
    @RequirePerm(value = "alarm:handle", risk = false)
    @Operation(summary = "处理安全事件")
    @PutMapping("/event/{id}/handle")
    public Result<Void> handleEvent(@PathVariable Long id, @RequestParam Integer status,
                                     @RequestParam(required = false) String remark) {
        securityEventService.handleEvent(id, status, remark);
        return Result.success();
    }

    // === 安全检测规则配置 ===

    /**
     * 查询所有安全检测规则
     *
     * @return 安全检测规则列表
     */
    @Operation(summary = "查询安全规则列表")
    @GetMapping("/rule/list")
    public Result<java.util.List<SecurityRule>> ruleList() {
        return Result.success(securityRuleService.listRules());
    }

    /**
     * 新增安全检测规则（高危）。
     *
     * <p>T02 权限点：{@code sys:security:update}</p>
     */
    @RequirePerm(value = "sys:security:update", risk = true)
    @Operation(summary = "新增安全规则")
    @PostMapping("/rule")
    public Result<SecurityRule> createRule(@RequestBody SecurityRule rule) {
        return Result.success(securityRuleService.createRule(rule));
    }

    /**
     * 更新安全检测规则（高危）。
     *
     * <p>T02 权限点：{@code sys:security:update}</p>
     */
    @RequirePerm(value = "sys:security:update", risk = true)
    @Operation(summary = "更新安全规则")
    @PutMapping("/rule/{id}")
    public Result<Void> updateRule(@PathVariable Long id, @RequestBody SecurityRule rule) {
        securityRuleService.updateRule(id, rule);
        return Result.success();
    }

    /**
     * 删除安全检测规则（高危）。
     *
     * <p>T02 权限点：{@code sys:security:update}</p>
     */
    @RequirePerm(value = "sys:security:update", risk = true)
    @Operation(summary = "删除安全规则")
    @DeleteMapping("/rule/{id}")
    public Result<Void> deleteRule(@PathVariable Long id) {
        securityRuleService.deleteRule(id);
        return Result.success();
    }

    /**
     * IP 封禁请求体
     */
    @Data
    public static class BanRequest {
        private String ipAddress;
        private Long appId;
        private String reason;
        private int durationMin;
    }
}
