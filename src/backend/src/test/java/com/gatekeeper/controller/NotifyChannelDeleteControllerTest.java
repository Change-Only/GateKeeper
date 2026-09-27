package com.gatekeeper.controller;

import com.gatekeeper.alarm.AlarmRuleController;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T07-A 补课单测：通知渠道「删除端点」+ 三域 test/delete 权限注解。
 *
 * <p>覆盖 T07-A 的唯一真实缺口与新权限点：
 * <ul>
 *   <li>{@code DELETE /notify-channel/{id}} 存在并正确委托 {@code service.delete}</li>
 *   <li>{@code notify_channel:delete} 权限点（risk=1）标注到位</li>
 *   <li>{@code notify_channel:test} / {@code alarm_rule:test} 外发端点补点（risk=1）</li>
 * </ul>
 *
 * <p>注意：本类**只读**地反射校验注解，不启动 Spring 上下文；权限点是否落库由种子 SQL 负责
 * （见 {@code docs/sql/t07a-seed-rules.sql}），二者共同保证「有注解必有码」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("T07-A 通知渠道删除 + 三域 test/delete 权限点")
class NotifyChannelDeleteControllerTest {

    @Mock
    private NotifyChannelService notifyChannelService;

    private NotifyChannelController controller;

    @BeforeEach
    void setUp() {
        controller = new NotifyChannelController(notifyChannelService);
    }

    @Test
    @DisplayName("delete 端点存在且委托 service.delete")
    void delete_callsService() {
        Result<Void> r = controller.delete(5L);
        assertEquals(200, r.getCode());
        verify(notifyChannelService, times(1)).delete(5L);
    }

    @Test
    @DisplayName("delete 标注 @RequirePerm notify_channel:delete 高危（破坏性）")
    void delete_hasDeletePermAnnotation() throws NoSuchMethodException {
        RequirePerm ann = NotifyChannelController.class.getMethod("delete", Long.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann, "delete 必须标注 @RequirePerm，否则新码 notify_channel:delete 形同虚设");
        assertEquals("notify_channel:delete", ann.value());
        assertEquals(true, ann.risk());
    }

    @Test
    @DisplayName("notify-channel test 标注 @RequirePerm notify_channel:test 高危（外发）")
    void notifyTest_hasTestPermAnnotation() throws NoSuchMethodException {
        RequirePerm ann = NotifyChannelController.class.getMethod("test", Long.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("notify_channel:test", ann.value());
        assertEquals(true, ann.risk());
    }

    @Test
    @DisplayName("alarm-rule test 标注 @RequirePerm alarm_rule:test 高危（外发）")
    void alarmTest_hasTestPermAnnotation() throws NoSuchMethodException {
        RequirePerm ann = AlarmRuleController.class.getMethod("test", Long.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("alarm_rule:test", ann.value());
        assertEquals(true, ann.risk());
    }

    @Test
    @DisplayName("端点契约回归：notify-channel(5) + alarm-rule(5) 必备方法齐全（block-rule 见既有 BlockRuleControllerTest）")
    void allRuleEndpointsExist() throws NoSuchMethodException {
        Class<?> nc = NotifyChannelController.class;
        assertNotNull(nc.getMethod("list", Integer.class));
        assertNotNull(nc.getMethod("create", NotifyChannel.class));
        assertNotNull(nc.getMethod("update", Long.class, NotifyChannel.class));
        assertNotNull(nc.getMethod("delete", Long.class));
        assertNotNull(nc.getMethod("test", Long.class));

        Class<?> ar = AlarmRuleController.class;
        assertNotNull(ar.getMethod("list", Integer.class));
        assertNotNull(ar.getMethod("create", com.gatekeeper.entity.AlarmRule.class));
        assertNotNull(ar.getMethod("update", Long.class, com.gatekeeper.entity.AlarmRule.class));
        assertNotNull(ar.getMethod("toggle", Long.class, Integer.class));
        assertNotNull(ar.getMethod("test", Long.class));
    }

    @Test
    @DisplayName("失败保护：controller.delete 抛出的异常原样透传（不吞异常）")
    void delete_propagatesException() {
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(notifyChannelService).delete(7L);
        try {
            controller.delete(7L);
            org.junit.jupiter.api.Assertions.fail("应向上抛出异常");
        } catch (IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }
    }
}
