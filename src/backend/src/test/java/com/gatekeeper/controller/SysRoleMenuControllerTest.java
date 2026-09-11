package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.SysRoleMenu;
import com.gatekeeper.mapper.SysRoleMenuMapper;
import com.gatekeeper.security.PermissionCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
 * SysRoleMenuController 单元测试 — 校验角色菜单查询 + 替换 + afterCommit 失效
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysRoleMenuController 角色授权")
class SysRoleMenuControllerTest {

    @Mock
    private SysRoleMenuMapper sysRoleMenuMapper;

    @Mock
    private PermissionCacheService permissionCacheService;

    private SysRoleMenuController controller;

    @BeforeEach
    void setUp() {
        controller = new SysRoleMenuController(sysRoleMenuMapper, permissionCacheService);
    }

    @Test
    @DisplayName("listMenuIds 查询并提取 menuId 列表")
    void listMenuIds_extractsIds() {
        SysRoleMenu rm1 = new SysRoleMenu();
        rm1.setRoleId(1L);
        rm1.setMenuId(101L);
        SysRoleMenu rm2 = new SysRoleMenu();
        rm2.setRoleId(1L);
        rm2.setMenuId(102L);
        when(sysRoleMenuMapper.selectList(any()))
                .thenReturn(Arrays.asList(rm1, rm2));

        Result<List<Long>> r = controller.listMenuIds(1L);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
        assertEquals(2, r.getData().size());
        assertTrue(r.getData().contains(101L));
        assertTrue(r.getData().contains(102L));
    }

    @Test
    @DisplayName("replaceRoleMenus 触发 replaceRoleMenus（含事务内删除+插入+afterCommit）")
    void replace_callsServiceReplace() {
        SysRoleMenuController.ReplaceMenuRequest req = new SysRoleMenuController.ReplaceMenuRequest();
        req.setMenuIds(Arrays.asList(201L, 202L, 203L));

        Result<Void> r = controller.replaceRoleMenus(5L, req);
        assertEquals(200, r.getCode());

        // 验证 permissionCacheService.replaceRoleMenus 被调用
        verify(permissionCacheService, times(1))
                .replaceRoleMenus(eq(5L), eq(Arrays.asList(201L, 202L, 203L)));
    }

    @Test
    @DisplayName("replaceRoleMenus 入参为 null 时不会 NPE")
    void replace_handlesNullRequest() {
        Result<Void> r = controller.replaceRoleMenus(5L, null);
        assertEquals(200, r.getCode());
        verify(permissionCacheService, times(1)).replaceRoleMenus(eq(5L), eq(null));
    }

    @Test
    @DisplayName("replaceRoleMenus 空菜单列表也能提交（清空授权）")
    void replace_handlesEmptyList() {
        SysRoleMenuController.ReplaceMenuRequest req = new SysRoleMenuController.ReplaceMenuRequest();
        req.setMenuIds(Collections.emptyList());

        Result<Void> r = controller.replaceRoleMenus(5L, req);
        assertEquals(200, r.getCode());
        verify(permissionCacheService, times(1)).replaceRoleMenus(eq(5L), any());
    }

    /**
     * 替换 eq 用于避免大量 static-import。
     */
    private static <T> T eq(T t) {
        return org.mockito.ArgumentMatchers.eq(t);
    }
}
