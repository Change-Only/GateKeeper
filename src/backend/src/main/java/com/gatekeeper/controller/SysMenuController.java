package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.SysMenu;
import com.gatekeeper.service.SysMenuService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 菜单权限点 Controller — T02 权限基座对外能力
 *
 * <p>负责 sys_menu 的 CRUD 与权限树查询：
 * <ul>
 *   <li>GET    /sys/menu/list           列出所有启用菜单</li>
 *   <li>GET    /sys/menu/perm-points    仅权限点（type=3），用于授权点选择器</li>
 *   <li>POST   /sys/menu                新增菜单/权限点</li>
 *   <li>PUT    /sys/menu/{id}           编辑菜单/权限点</li>
 *   <li>DELETE /sys/menu/{id}           删除菜单/权限点</li>
 * </ul>
 * </p>
 *
 * <p>权限点：新增/编辑/删除均需 {@code sys:role:grant}（角色授权页面的写能力）。</p>
 *
 * @author GateKeeper
 * @since T02 (APIM V2)
 */
@RestController
@RequestMapping("/sys/menu")
@RequiredArgsConstructor
@Tag(name = "菜单权限点", description = "菜单与权限点管理")
public class SysMenuController {

    private final SysMenuService sysMenuService;

    /**
     * 列出所有启用的菜单（按 sort_order 升序）。
     */
    @GetMapping("/list")
    public Result<List<SysMenu>> list() {
        return Result.success(sysMenuService.listEnabled());
    }
}
