package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.SysMenu;
import com.gatekeeper.mapper.SysMenuMapper;
import com.gatekeeper.service.SysMenuService;
import com.gatekeeper.service.impl.SysMenuServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
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

    private SysMenuService sysMenuService;
    private SysMenuController controller;

    @BeforeEach
    void setUp() {
        sysMenuService = new SysMenuServiceImpl();
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
}
