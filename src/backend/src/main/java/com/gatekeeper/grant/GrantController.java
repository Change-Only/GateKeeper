package com.gatekeeper.grant;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;

/**
 * 授权管理 Controller — T04-A 授权（审批流）对外能力
 *
 * <p>接口路径前缀：{@code /grant}
 * <ul>
 *   <li>GET    /grant/list               按 app/api/env/status 筛选</li>
 *   <li>GET    /grant/{id}               详情</li>
 *   <li>POST   /grant/create             创建（待审批，{@code grant:create} 高危）</li>
 *   <li>POST   /grant/{id}/approve       审批通过（{@code grant:approve} 高危）</li>
 *   <li>POST   /grant/{id}/reject         审批驳回（{@code grant:reject} 高危）</li>
 *   <li>POST   /grant/{id}/revoke         撤销（{@code grant:revoke} 高危）</li>
 *   <li>POST   /grant/{id}/renew          延期</li>
 *   <li>GET    /grant/pending             待审批列表</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/grant")
@RequiredArgsConstructor
@Tag(name = "授权管理", description = "应用接口授权（审批流）T04-A")
public class GrantController {

    private final GrantService grantService;

    @Operation(summary = "授权列表查询")
    @GetMapping("/list")
    public Result<List<AppApiGrant>> list(
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) Long apiId,
            @RequestParam(required = false) String envCode,
            @RequestParam(required = false) Integer status) {
        return Result.success(grantService.listGrants(appId, apiId, envCode, status));
    }

    @Operation(summary = "授权详情")
    @GetMapping("/{id}")
    public Result<AppApiGrant> detail(@PathVariable Long id) {
        return Result.success(grantService.getGrant(id));
    }

    @RequirePerm(value = "grant:create", risk = true)
    @Operation(summary = "创建授权（待审批）")
    @PostMapping("/create")
    public Result<AppApiGrant> create(@Valid @RequestBody AppApiGrant grant) {
        return Result.success(grantService.createGrant(grant));
    }

    @RequirePerm(value = "grant:approve", risk = true)
    @Operation(summary = "审批通过")
    @PostMapping("/{id}/approve")
    public Result<AppApiGrant> approve(@PathVariable Long id, @Valid @RequestBody GrantAuditRequest req) {
        return Result.success(grantService.approve(id, req.getAuditRemark(), req.getAuditorId(), req.getAuditorName()));
    }

    @RequirePerm(value = "grant:reject", risk = true)
    @Operation(summary = "审批驳回")
    @PostMapping("/{id}/reject")
    public Result<AppApiGrant> reject(@PathVariable Long id, @Valid @RequestBody GrantRejectRequest req) {
        return Result.success(grantService.reject(id, req.getAuditRemark(), null, null));
    }

    @RequirePerm(value = "grant:revoke", risk = true)
    @Operation(summary = "撤销授权")
    @PostMapping("/{id}/revoke")
    public Result<AppApiGrant> revoke(@PathVariable Long id, @Valid @RequestBody GrantRevokeRequest req) {
        return Result.success(grantService.revoke(id, req.getRevokeReason(), null, null));
    }

    @RequirePerm(value = "grant:approve", risk = true)
    @Operation(summary = "延期授权")
    @PostMapping("/{id}/renew")
    public Result<AppApiGrant> renew(@PathVariable Long id, @Valid @RequestBody GrantRenewRequest req) {
        return Result.success(grantService.renew(id, req.getValidTo()));
    }

    @Operation(summary = "待审批列表")
    @GetMapping("/pending")
    public Result<List<AppApiGrant>> pending(@RequestParam(required = false) String envCode) {
        return Result.success(grantService.listPending(envCode));
    }

    // ===================== 请求体（内联 DTO，供 GrantController / PermissionController 复用） =====================

    /** 审批请求：审批人 + 审批意见 */
    @lombok.Data
    public static class GrantAuditRequest {
        private Long auditorId;
        private String auditorName;
        private String auditRemark;
    }

    /** 驳回请求：审批意见必填 */
    @lombok.Data
    public static class GrantRejectRequest {
        @NotBlank(message = "审批意见不能为空")
        private String auditRemark;
    }

    /** 撤销请求：撤销原因必填 */
    @lombok.Data
    public static class GrantRevokeRequest {
        @NotBlank(message = "撤销原因不能为空")
        private String revokeReason;
    }

    /** 延期请求：新的失效日期（可空，缺省 +30 天） */
    @lombok.Data
    public static class GrantRenewRequest {
        private LocalDate validTo;
    }
}
