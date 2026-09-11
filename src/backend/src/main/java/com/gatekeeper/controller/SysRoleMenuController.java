package com.gatekeeper.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.SysRoleMenu;
import com.gatekeeper.security.PermissionCacheService;
import com.gatekeeper.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色-权限点（菜单）授权 Controller — T02 权限基座
 *
 * <p>负责 sys_role_menu 的读写，包括：
 * <ul>
 *   <li>GET /sys/role-menu/{roleId}              查某角色的菜单 ID 集合</li>
 *   <li>POST /sys/role-menu/{roleId}/replace     替换某角色的菜单（事务内全删全插）</li>
 * </ul>
 * </p>
 *
 * <p>替换操作使用 {@link PermissionCacheService#replaceRoleMenus(Long, java.util.List)}
 * 在事务 afterCommit DEL 该角色下全部用户的 gk:perm:{uid} 缓存。</p>
 *
 * @author GateKeeper
 * @since T02 (APIM V2)
 */
@RestController
@RequestMapping("/sys/role-menu")
@RequiredArgsConstructor
@Tag(name = "角色授权", description = "角色-权限点授权")
public class SysRoleMenuController {

    private final com.gatekeeper.mapper.SysRoleMenuMapper sysRoleMenuMapper;
    private final PermissionCacheService permissionCacheService;

    /**
     * 查询某角色已授权的菜单 ID 列表。
     */
    @GetMapping("/{roleId}")
    public Result<List<Long>> listMenuIds(@PathVariable Long roleId) {
        List<SysRoleMenu> mappings = sysRoleMenuMapper.selectList(
                new QueryWrapper<SysRoleMenu>().eq("role_id", roleId));
        List<Long> menuIds = mappings.stream()
                .map(SysRoleMenu::getMenuId)
                .collect(Collectors.toList());
        return Result.success(menuIds);
    }

    /**
     * 替换某角色的菜单集合（事务内全删全插 + afterCommit DEL 缓存）。
     */
    @RequirePerm(value = "sys:role:grant")
    @Operation(summary = "替换角色菜单授权")
    @PostMapping("/{roleId}/replace")
    public Result<Void> replaceRoleMenus(@PathVariable Long roleId,
                                         @RequestBody ReplaceMenuRequest request) {
        // request.getMenuIds() 可能为 null（替换为空）；统一处理
        List<Long> menuIds = request == null ? null : request.getMenuIds();
        permissionCacheService.replaceRoleMenus(roleId, menuIds);
        return Result.success();
    }

    /**
     * 角色菜单替换请求体。
     */
    @Data
    public static class ReplaceMenuRequest {
        /** 角色新的菜单 ID 集合；null 或空集合表示清空授权 */
        private List<Long> menuIds;
    }
}
