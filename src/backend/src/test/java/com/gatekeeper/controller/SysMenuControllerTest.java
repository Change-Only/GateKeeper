package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.SysMenu;
import com.gatekeeper.mapper.SysMenuMapper;
import com.gatekeeper.service.SysMenuService;
import com.gatekeeper.service.impl.SysMenuServiceImpl;
import com.gatekeeper.mapper.SysRoleMenuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SysMenuController 单元测试 — 校验 CRUD 五个接口
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysMenuController CRUD")
class SysMenuControllerTest {

    @Mock
    private SysMenuMapper sysMenuMapper;
    @Mock
    private SysRoleMenuMapper sysRoleMenuMapper;

    private SysMenuService sysMenuService;
    private SysMenuController controller;

    @BeforeEach
    void setUp() {
        sysMenuService = new SysMenuServiceImpl(sysRoleMenuMapper);
        // 用反射把 mock mapper 注入 ServiceImpl 的 baseMapper
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(sysMenuService, sysMenuMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        controller = new SysMenuController(sysMenuService);
    }

    @Test
    @DisplayName("list 调用 listEnabled")
    void list_callsListEnabled() {
        SysMenu m = new SysMenu();
        m.setId(1L);
        m.setName("查看应用");
        m.setPermCode("app:list");
        m.setType(3);
        when(sysMenuMapper.selectList(any())).thenReturn(Collections.singletonList(m));

        Result<List<SysMenu>> r = controller.list();
        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
        assertEquals(1, r.getData().size());
        assertEquals("app:list", r.getData().get(0).getPermCode());
    }

    @Test
    @DisplayName("permPoints 调用 listByType(3)")
    void permPoints_callListByType3() {
        SysMenu m = new SysMenu();
        m.setId(31L);
        m.setPermCode("api:list");
        m.setType(3);
        when(sysMenuMapper.selectList(any())).thenReturn(Collections.singletonList(m));

        Result<List<SysMenu>> r = controller.permPoints();
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().size());
        assertEquals("api:list", r.getData().get(0).getPermCode());
    }

    @Test
    @DisplayName("create 调用 baseMapper.insert")
    void create_insertsRow() {
        SysMenu menu = new SysMenu();
        menu.setName("新增权限点");
        menu.setPermCode("test:create");
        menu.setType(3);
        menu.setRiskFlag(0);

        Result<SysMenu> r = controller.create(menu);
        assertEquals(200, r.getCode());
        verify(sysMenuMapper, times(1)).insert(any(SysMenu.class));
    }

    @Test
    @DisplayName("update 调用 baseMapper.updateById")
    void update_callsMapperUpdateById() {
        SysMenu menu = new SysMenu();
        menu.setName("改名");
        controller.update(99L, menu);
        verify(sysMenuMapper, times(1)).updateById(any(SysMenu.class));
    }

    @Test
    @DisplayName("delete 先删 sys_role_menu 再删菜单（级联）")
    void delete_cascadesToRoleMenu() {
        controller.delete(7L);
        verify(sysRoleMenuMapper, times(1)).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(sysMenuMapper, times(1)).deleteById(7L);
    }
}
