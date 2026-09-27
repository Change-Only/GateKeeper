package com.gatekeeper.controller;

import com.gatekeeper.alarm.NotifyChannelController;
import com.gatekeeper.alarm.NotifyChannelService;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.NotifyChannel;
import com.gatekeeper.security.RequirePerm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotifyChannelController 端到端单测 — 覆盖 5 个接口 + 权限注解
 *
 * <p>重点验证 create 标注 {@code @RequirePerm("notify_channel:create", risk=true)}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotifyChannelController 端到端 + 权限点")
class NotifyChannelControllerTest {

    @Mock
    private NotifyChannelService notifyChannelService;

    private NotifyChannelController controller;

    @BeforeEach
    void setUp() {
        controller = new NotifyChannelController(notifyChannelService);
    }

    @Test
    @DisplayName("list 调用 service.list")
    void list_callsService() {
        when(notifyChannelService.list(any())).thenReturn(Collections.emptyList());
        Result<List<NotifyChannel>> r = controller.list(null);
        assertEquals(200, r.getCode());
        verify(notifyChannelService, times(1)).list(null);
    }
    @Test
    @DisplayName("create 调用 service.create")
    void create_callsService() {
        NotifyChannel ch = new NotifyChannel();
        ch.setId(1L);
        ch.setChannelName("企业微信");
        when(notifyChannelService.create(any(NotifyChannel.class))).thenReturn(ch);
        Result<NotifyChannel> r = controller.create(ch);
        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
    }

    @Test
    @DisplayName("create 标注 @RequirePerm notify_channel:create 高危")
    void create_hasCreatePermAnnotation() throws NoSuchMethodException {
        RequirePerm ann = NotifyChannelController.class.getMethod("create", NotifyChannel.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("notify_channel:create", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("update 调用 service.update")
    void update_callsService() {
        NotifyChannel ch = new NotifyChannel();
        ch.setChannelName("新名称");
        Result<Void> r = controller.update(5L, ch);
        assertEquals(200, r.getCode());
        verify(notifyChannelService, times(1)).update(eq(5L), eq(ch));
    }

    @Test
    @DisplayName("test 调用 service.test")
    void test_callsService() {
        Result<Void> r = controller.test(5L);
        assertEquals(200, r.getCode());
        verify(notifyChannelService, times(1)).test(5L);
    }

    @Test
    @DisplayName("4 个接口全部存在（端到端路由核查）")
    void allEndpointsExist() throws NoSuchMethodException {
        Class<?> c = NotifyChannelController.class;
        assertNotNull(c.getMethod("list", Integer.class));
        assertNotNull(c.getMethod("create", NotifyChannel.class));
        assertNotNull(c.getMethod("update", Long.class, NotifyChannel.class));
        assertNotNull(c.getMethod("test", Long.class));
    }

    private static void assertTrue(boolean b) {
        assertEquals(true, b);
    }
}
