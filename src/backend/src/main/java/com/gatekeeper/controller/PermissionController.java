package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.grant.GrantController;
import com.gatekeeper.grant.GrantService;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.ApiGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限管理 Controller — T04-A 迁移至授权域（app_api_grant）
 *
 * <p>保留既有公开契约（/list、/grant、/batch、/revoke，以及 /approve 占位、/grant-by-group），
 * 但底层读写从 app_api_permission 切换到 app_api_grant。新增基于授权主键的审批流端点：
 * /permission/{id}/approve、/permission/{id}/reject、/permission/{id}/renew、/permission/pending。</p>
 *
 * <p>接口路径前缀：{@code /permission}
 * <ul>
 *   <li>GET    /permission/list                查询授权列表（按 app/interface 筛选）</li>
 *   <li>POST   /permission                     单条授权（{@code grant:create}）</li>
 *   <li>POST   /permission/batch               批量授权</li>
 *   <li>POST   /permission/grant-by-group      按分组授权</li>
 *   <li>DELETE /permission                    撤销授权（{@code grant:revoke} 高危）</li>
 *   <li>POST   /permission/approve             T02 兼容占位（审批流已迁移至 /permission/{id}/approve）</li>
 *   <li>POST   /permission/{id}/approve        审批通过（{@code grant:approve} 高危）</li>
 *   <li>POST   /permission/{id}/reject         审批驳回（{@code grant:reject} 高危）</li>
 *   <li>POST   /permission/{id}/renew          延期</li>
 *   <li>GET    /permission/pending             待审批列表</li>
 * </ul>
 */
@RestController
@RequestMapping("/permission")
@RequiredArgsConstructor
@Tag(name = "权限", description = "权限管理接口（T04-A 授权域）")
public class PermissionController {

    private final GrantService grantService;

    /**
     * 查询授权列表（interfaceId 映射为 apiId，底层改读 app_api_grant）。
     */
    @Operation(summary = "分页查询权限列表")
    @GetMapping("/list")
    public Result<List<AppApiGrant>> list(
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) Long interfaceId) {
        return Result.success(grantService.listGrants(appId, interfaceId, null, null));
    }

    /**
     * 单条授权（授予应用对指定接口的访问授权，默认进入待审批）。
     */
    @RequirePerm(value = "grant:create")
    @PostMapping
    public Result<Void> grant(@RequestBody AppApiGrant grant) {
        grantService.createGrant(grant);
        return Result.success();
    }

    /**
     * 批量授权（一次性授予应用对多个接口的访问授权）。
     */
    @RequirePerm(value = "grant:create")
    @Operation(summary = "批量授权")
    @PostMapping("/batch")
    public Result<Void> batchGrant(@RequestBody BatchGrantRequest request) {
        grantService.batchCreate(request.getAppId(), request.getInterfaceIds());
        return Result.success();
    }

    /**
     * 按分组授权（授予应用对某分组下所有接口的访问授权）。
     */
    @RequirePerm(value = "grant:create")
    @Operation(summary = "按分组授权")
    @PostMapping("/grant-by-group")
    public Result<Void> grantByGroup(@RequestBody GrantByGroupRequest request) {
        grantService.createByGroup(request.getAppId(), request.getGroupId());
        return Result.success();
    }

    /**
     * 撤销授权（移除应用对指定接口的访问授权；底层改走 app_api_grant）。
     */
    @RequirePerm(value = "grant:revoke", risk = true)
    @DeleteMapping
    public Result<Void> revoke(@RequestParam Long appId, @RequestParam Long interfaceId) {
        grantService.revokeByAppAndApi(appId, interfaceId);
        return Result.success();
    }

    /**
     * 审批授权（T02 占位接口）：审批流已在 T04-A 迁移至 {@code /permission/{id}/approve}。
     */
    @RequirePerm(value = "grant:approve")
    @PostMapping("/approve")
    public Result<Void> approve(@RequestBody AppApiGrant grant) {
        return Result.success();
    }

    // =====================================================================
    // T04-A 新增：基于授权主键的审批流端点
    // =====================================================================

    /**
     * 审批通过授权（高危）。
     */
    @RequirePerm(value = "grant:approve", risk = true)
    @Operation(summary = "审批通过授权")
    @PostMapping("/{id}/approve")
    public Result<AppApiGrant> approveById(@PathVariable Long id,
            @RequestBody GrantController.GrantAuditRequest req) {
        return Result.success(grantService.approve(id, req.getAuditRemark(), req.getAuditorId(), req.getAuditorName()));
    }

    /**
     * 驳回授权（高危，必须填写审批意见）。
     */
    @RequirePerm(value = "grant:reject", risk = true)
    @Operation(summary = "驳回授权")
    @PostMapping("/{id}/reject")
    public Result<AppApiGrant> rejectById(@PathVariable Long id,
            @RequestBody GrantController.GrantRejectRequest req) {
        return Result.success(grantService.reject(id, req.getAuditRemark(), null, null));
    }

    /**
     * 延期授权（高危 —— P0-2：延期实质是「重新批准有效期」，与审批同风险级，复用 {@code grant:approve}）。
     */
    @RequirePerm(value = "grant:approve", risk = true)
    @Operation(summary = "延期授权")
    @PostMapping("/{id}/renew")
    public Result<AppApiGrant> renewById(@PathVariable Long id,
            @RequestBody GrantController.GrantRenewRequest req) {
        return Result.success(grantService.renew(id, req.getValidTo()));
    }

    /**
     * 待审批授权列表。
     */
    @Operation(summary = "待审批授权列表")
    @GetMapping("/pending")
    public Result<List<AppApiGrant>> pending(@RequestParam(required = false) String envCode) {
        return Result.success(grantService.listPending(envCode));
    }

    /**
     * 批量授权请求体
     */
    @lombok.Data
    public static class BatchGrantRequest {
        private Long appId;
        private List<Long> interfaceIds;
    }

    /**
     * 按分组授权请求体
     */
    @lombok.Data
    public static class GrantByGroupRequest {
        private Long appId;
        private Long groupId;
    }
}
