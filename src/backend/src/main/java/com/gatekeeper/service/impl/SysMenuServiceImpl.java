package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.entity.SysMenu;
import com.gatekeeper.entity.SysRoleMenu;
import com.gatekeeper.mapper.SysMenuMapper;
import com.gatekeeper.mapper.SysRoleMenuMapper;
import com.gatekeeper.service.SysMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单权限点服务实现 — sys_menu CRUD 与级联删除
 *
 * @author GateKeeper
 * @since T02 (APIM V2)
 */
@Service
@RequiredArgsConstructor
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements SysMenuService {

    private final SysRoleMenuMapper sysRoleMenuMapper;

    @Override
    public List<SysMenu> listEnabled() {
        return baseMapper.selectList(
                new QueryWrapper<SysMenu>().eq("status", 1).orderByAsc("sort_order"));
    }

    @Override
    public List<SysMenu> listByType(Integer type) {
        return baseMapper.selectList(
                new QueryWrapper<SysMenu>()
                        .eq("type", type)
                        .eq("status", 1)
                        .orderByAsc("sort_order"));
    }

    @Override
    public SysMenu createMenu(SysMenu menu) {
        if (menu.getStatus() == null) {
            menu.setStatus(1);
        }
        menu.setCreatedAt(LocalDateTime.now());
        baseMapper.insert(menu);
        return menu;
    }

    @Override
    public void updateMenu(Long id, SysMenu menu) {
        menu.setId(id);
        // 不允许通过 update 修改 created_at
        menu.setCreatedAt(null);
        baseMapper.updateById(menu);
    }

    /**
     * 删除菜单/权限点时级联删除 sys_role_menu 关联。
     */
    @Override
    @Transactional
    public void deleteMenu(Long id) {
        // 1) 删除 sys_role_menu 关联
        sysRoleMenuMapper.delete(
                new QueryWrapper<SysRoleMenu>().eq("menu_id", id));
        // 2) 删除菜单
        baseMapper.deleteById(id);
    }
}
