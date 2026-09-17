package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.dto.OperationLogOptionsVo;
import com.gatekeeper.entity.SysOperationLog;
import com.gatekeeper.entity.SysRole;
import com.gatekeeper.entity.SysUser;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.SysOperationLogService;
import com.gatekeeper.service.SysRoleService;
import com.gatekeeper.service.SysUserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 系统管理 Controller（用户管理、角色管理、操作审计日志）
 *
 * <p>负责系统基础管理功能，业务模块包括三部分：系统用户管理、角色管理，
 * 以及操作审计日志的查询。</p>
 *
 * <p>T02 收尾：
 * <ul>
 *   <li>{@code /system/user/{id}/status/{status}} 已加 {@code sys:user:update}（用户停用高危）</li>
 *   <li>{@code /system/operation-log/export} 已加 {@code audit:export}（审计导出高危）</li>
 * </ul>
 * </p>
 *
 * <p>主要接口路径前缀：{@code /system}
 * <ul>
 *   <li>/system/user/*           用户管理（列表/新增/编辑/启停/删除/重置密码）</li>
 *   <li>/system/role/*           角色管理（列表/新增/编辑/删除）</li>
 *   <li>/system/operation-log/*  操作审计日志查询/导出</li>
 * </ul>
 */
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
@Tag(name = "系统管理", description = "系统管理管理接口")
public class SystemController {

    private final SysUserService sysUserService;
    private final SysRoleService sysRoleService;
    private final SysOperationLogService sysOperationLogService;

    // === 用户管理 ===

    /**
     * 分页查询系统用户列表
     *
     * @param current  当前页码（默认第 1 页）
     * @param size     每页条数（默认 10 条）
     * @param username 用户名（可选，模糊匹配）
     * @return 分页结果，包含系统用户列表及总数
     */
    @Operation(summary = "分页查询用户列表")
    @GetMapping("/user/list")
    public Result<PageResult<SysUser>> userList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String username) {
        return Result.success(sysUserService.pageQuery(current, size, username));
    }

    /**
     * 新增系统用户
     *
     * @param user 系统用户实体（含用户名、密码、角色等）
     * @return 创建成功后的用户实体
     */
    @Operation(summary = "新增用户")
    @RequirePerm(value = "sys:user:create", risk = true)
    @PostMapping("/user")
    public Result<SysUser> createUser(@RequestBody SysUser user) {
        return Result.success(sysUserService.createUser(user));
    }

    /**
     * 编辑系统用户信息
     *
     * @param id   用户 ID
     * @param user 待更新的用户信息
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "更新用户")
    @RequirePerm(value = "sys:user:update", risk = true)
    @PutMapping("/user/{id}")
    public Result<Void> updateUser(@PathVariable Long id, @RequestBody SysUser user) {
        sysUserService.updateUser(id, user);
        return Result.success();
    }

    /**
     * 启用/停用系统用户（高危：T02 映射为 sys:user:update）。
     *
     * <p>T02 权限点：{@code sys:user:update}（PRD 中归为用户管理类高危）</p>
     *
     * @param id     用户 ID
     * @param status 目标状态（如 1 启用、0 停用）
     * @return 操作结果（无业务数据返回）
     */
    @RequirePerm(value = "sys:user:update", risk = true)
    @Operation(summary = "更新用户状态")
    @PutMapping("/user/{id}/status/{status}")
    public Result<Void> updateUserStatus(@PathVariable Long id, @PathVariable Integer status) {
        sysUserService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 删除系统用户
     *
     * @param id 用户 ID
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "删除用户")
    @RequirePerm(value = "sys:user:delete", risk = true)
    @DeleteMapping("/user/{id}")
    public Result<Void> deleteUser(@PathVariable Long id) {
        sysUserService.deleteUser(id);
        return Result.success();
    }

    /**
     * 重置系统用户密码
     *
     * @param id      用户 ID
     * @param request 重置密码请求（含新密码）
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "重置用户密码")
    @RequirePerm(value = "sys:user:resetpwd", risk = true)
    @PutMapping("/user/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody ResetPasswordRequest request) {
        sysUserService.resetPassword(id, request.getPassword());
        return Result.success();
    }

    // === 角色管理 ===

    /**
     * 查询所有角色
     *
     * @return 系统角色列表
     */
    @Operation(summary = "查询角色列表")
    @GetMapping("/role/list")
    public Result<List<SysRole>> roleList() {
        return Result.success(sysRoleService.listRoles());
    }

    /**
     * 新增角色
     *
     * @param role 角色实体（含角色名、权限等）
     * @return 创建成功后的角色实体
     */
    @Operation(summary = "新增角色")
    @RequirePerm(value = "sys:role:create", risk = true)
    @PostMapping("/role")
    public Result<SysRole> createRole(@RequestBody SysRole role) {
        return Result.success(sysRoleService.createRole(role));
    }

    /**
     * 编辑角色
     *
     * @param id   角色 ID
     * @param role 待更新的角色信息
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "更新角色")
    @RequirePerm(value = "sys:role:update", risk = true)
    @PutMapping("/role/{id}")
    public Result<Void> updateRole(@PathVariable Long id, @RequestBody SysRole role) {
        sysRoleService.updateRole(id, role);
        return Result.success();
    }

    /**
     * 删除角色
     *
     * @param id 角色 ID
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "删除角色")
    @RequirePerm(value = "sys:role:delete", risk = true)
    @DeleteMapping("/role/{id}")
    public Result<Void> deleteRole(@PathVariable Long id) {
        sysRoleService.deleteRole(id);
        return Result.success();
    }

    // === 操作审计日志 ===

    /**
     * 查询审计页筛选下拉的候选项（操作模块 / 操作类型）。
     *
     * <p>返回库里**真实出现过**的去重值，供「操作模块」「操作类型」两个下拉渲染。</p>
     *
     * <p><b>为何必须服务端下发</b>：{@code operation_module} 取值由
     * {@code OperationLogAspect#firstSegment(requestURI)} 从 controller 路径首段推导，
     * 值域**随 controller 增减而变**；前端写死必然过期（2026-09-17 实测：前端 5 项 vs 库里 18 项，
     * 14 个模块的记录无法筛选；且前端写死的 {@code LOGIN}/{@code LOGOUT} 永远不会出现）。</p>
     *
     * <p><b>权限点：不加。</b>与同文件的 {@code /operation-log/list} 保持同一裁定 ——
     * 只读且不涉及任何写操作/敏感字段，能查到列表的人本就能看到这些取值，
     * 加权限点只会让下拉变空（"无权查候选项但有权查数据"是自相矛盾的闸门）。
     * 对齐先例：T11 {@code GET /alarm-rule/target-options} 同样零新增权限点。</p>
     *
     * @return 候选项，两个字段均非 null
     */
    @Operation(summary = "查询审计筛选候选项（操作模块 / 操作类型）")
    @GetMapping("/operation-log/filter-options")
    public Result<OperationLogOptionsVo> operationLogFilterOptions() {
        return Result.success(sysOperationLogService.filterOptions());
    }

    /**
     * 分页查询操作审计日志
     *
     * @param current         当前页码（默认第 1 页）
     * @param size            每页条数（默认 10 条）
     * @param operationType   操作类型（可选）
     * @param operationModule 操作模块（可选）
     * @return 分页结果，包含操作日志列表及总数
     */
    @Operation(summary = "分页查询操作审计列表")
    @GetMapping("/operation-log/list")
    public Result<PageResult<SysOperationLog>> operationLogList(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String operationModule) {
        return Result.success(sysOperationLogService.pageQuery(current, size, operationType, operationModule));
    }

    /**
     * 导出审计日志为 CSV（高危）。
     *
     * <p>T02 权限点：{@code audit:export}</p>
     * <p>MVP 阶段：直接渲染当前过滤条件命中的全量日志为 CSV（UTF-8 BOM）。
     * 生产环境建议改用 ExportTaskService 异步导出 + 邮件通知。</p>
     *
     * @param operationType   操作类型（可选）
     * @param operationModule 操作模块（可选）
     * @param response        HTTP 响应（直接写入 CSV 流）
     */
    @RequirePerm(value = "audit:export", risk = true)
    @Operation(summary = "导出审计日志 CSV")
    @GetMapping("/operation-log/export")
    public void exportOperationLog(
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String operationModule,
            HttpServletResponse response) throws IOException {

        // 1) 设置响应头
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=operation-log.csv");

        // 2) 拉数据（MVP 直接全量，未来分页）
        PageResult<SysOperationLog> page = sysOperationLogService.pageQuery(
                1, 10000, operationType, operationModule);
        List<SysOperationLog> rows = page.getRecords();

        // 3) 写 CSV（带 UTF-8 BOM 让 Excel 不乱码）
        try (Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write("\uFEFF"); // BOM
            w.write("ID,操作员ID,操作员,操作类型,操作模块,操作描述,时间\n");
            for (SysOperationLog log : rows) {
                w.write(safe(log.getId()) + ",");
                w.write(safe(log.getOperatorId()) + ",");
                w.write(csv(log.getOperatorName()) + ",");
                w.write(csv(log.getOperationType()) + ",");
                w.write(csv(log.getOperationModule()) + ",");
                w.write(csv(log.getOperationDesc()) + ",");
                w.write(csv(log.getCreatedAt() == null ? "" : log.getCreatedAt().toString()));
                w.write("\n");
            }
            w.flush();
        }
    }

    private static String safe(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * 重置密码请求体
     */
    @Data
    public static class ResetPasswordRequest {
        private String password;
    }
}
