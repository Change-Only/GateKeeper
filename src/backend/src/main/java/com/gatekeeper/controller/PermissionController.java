package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.grant.GrantService;
import com.gatekeeper.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
     * 批量授权（一次性授予应用对多个接口的访问授权）。
     */
    @RequirePerm(value = "grant:create")
    @Operation(summary = "批量授权")
    @PostMapping("/batch")
    public Result<Void> batchGrant(@RequestBody BatchGrantRequest request) {
        grantService.batchCreate(request.getAppId(), request.getInterfaceIds());
        return Result.success();
    }
    // =====================================================================
    // T04-A 新增：基于授权主键的审批流端点
    // =====================================================================
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
