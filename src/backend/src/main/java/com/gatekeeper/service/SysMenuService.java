package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.entity.SysMenu;

import java.util.List;

/**
 * 菜单权限点服务接口 — 负责 sys_menu 的增删改查与权限树构建
 *
 * <p>T02 权限基座核心服务之一。</p>
 *
 * @author GateKeeper
 * @since T02 (APIM V2)
 */
public interface SysMenuService extends IService<SysMenu> {

    /**
     * 查询所有启用的菜单权限点（status=1）。
     */
    List<SysMenu> listEnabled();
}
