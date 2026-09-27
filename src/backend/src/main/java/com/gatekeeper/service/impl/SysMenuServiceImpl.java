package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.entity.SysMenu;
import com.gatekeeper.mapper.SysMenuMapper;
import com.gatekeeper.service.SysMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    @Override
    public List<SysMenu> listEnabled() {
        return baseMapper.selectList(
                new QueryWrapper<SysMenu>().eq("status", 1).orderByAsc("sort_order"));
    }
}
