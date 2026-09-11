package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SysRoleMenu;

/**
 * 角色-权限点关联表 Mapper — 负责 sys_role_menu 表的数据访问
 *
 * <p>由 PermissionCacheService 通过此 Mapper 拉取角色的菜单授权集合。</p>
 *
 * @author GateKeeper
 * @since T02 (APIM V2)
 */
public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenu> {
}
