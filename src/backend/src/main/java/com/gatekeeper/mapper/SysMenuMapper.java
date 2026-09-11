package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SysMenu;

/**
 * 菜单权限点表 Mapper — 负责 sys_menu 表的数据访问
 *
 * <p>由 PermissionCacheService 通过此 Mapper 拉取用户的权限点（type=3）集合。</p>
 *
 * @author GateKeeper
 * @since T02 (APIM V2)
 */
public interface SysMenuMapper extends BaseMapper<SysMenu> {
}
