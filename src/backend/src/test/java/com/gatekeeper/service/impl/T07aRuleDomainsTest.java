package com.gatekeeper.service.impl;

import com.gatekeeper.alarm.AlarmRuleController;
import com.gatekeeper.alarm.NotifyChannelController;
import com.gatekeeper.alarm.NotifyChannelService;
import com.gatekeeper.alarm.NotifySender;
import com.gatekeeper.alarm.impl.NotifyChannelServiceImpl;
import com.gatekeeper.block.BlockRuleController;
import com.gatekeeper.block.ManualBlockRequest;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.AlarmRule;
import com.gatekeeper.entity.BlockRule;
import com.gatekeeper.entity.NotifyChannel;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.NotifyChannelMapper;
import com.gatekeeper.security.RequirePerm;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T07-A 三域（告警规则 / 阻断规则 / 通知渠道）契约与权限点单测。
 *
 * <p>本轮结论：三域 Controller 早已存在于 {@code com.gatekeeper.alarm} / {@code com.gatekeeper.block}
 * 子包，18 个端点中 17 个路径与前端 {@code api/modules.js} 一致；T07-A 真实缺口是
 * {@code DELETE /notify-channel/{id}} 与 3 个权限点（alarm_rule:test / notify_channel:test /
 * notify_channel:delete）。本测试把「新增端点 + 权限策略 + 既有端点不回归」三件事钉死。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("T07-A 告警规则/阻断规则/通知渠道 三域契约与权限点")
class T07aRuleDomainsTest {

    @Mock
    private NotifyChannelService notifyChannelService;

    @Mock
    private NotifyChannelMapper notifyChannelMapper;

    @Mock
    private com.gatekeeper.crypto.CryptoService cryptoService;

    @Mock
    private NotifySender notifySender;

    private NotifyChannelController notifyChannelController;
    private NotifyChannelServiceImpl notifyChannelServiceImpl;

    @BeforeEach
    void setUp() throws Exception {
        notifyChannelController = new NotifyChannelController(notifyChannelService);
        // T09-N1 起构造函数增加 CryptoService（配置加密链路）
        notifyChannelServiceImpl = new NotifyChannelServiceImpl(notifySender, cryptoService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                notifyChannelServiceImpl, "aesDbKey", "TEST_32_BYTES_LONG_KEY_FOR_AES_256");
        // MyBatis-Plus ServiceImpl 的 baseMapper 由容器注入，单测需显式反射塞入
        Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                .getDeclaredField("baseMapper");
        baseMapperField.setAccessible(true);
        baseMapperField.set(notifyChannelServiceImpl, notifyChannelMapper);
    }

    // ===================== 1) 新增端点 DELETE /notify-channel/{id} =====================

    @Test
    @DisplayName("DELETE /notify-channel/{id} 调用 service.delete 并返回成功")
    void delete_callsServiceAndReturnsSuccess() {
        Result<Void> r = notifyChannelController.delete(7L);
        assertEquals(200, r.getCode());
        verify(notifyChannelService, times(1)).delete(eq(7L));
    }

    @Test
    @DisplayName("delete 服务：渠道存在时执行物理删除")
    void delete_existingChannel_deletesRow() {
        NotifyChannel existing = new NotifyChannel();
        existing.setId(7L);
        existing.setChannelName("企业微信");
        when(notifyChannelMapper.selectById(7L)).thenReturn(existing);

        notifyChannelServiceImpl.delete(7L);

        verify(notifyChannelMapper, times(1)).deleteById(eq(7L));
    }

    @Test
    @DisplayName("delete 服务：渠道不存在时抛 404，不静默成功")
    void delete_missingChannel_throwsNotFound() {
        when(notifyChannelMapper.selectById(999L)).thenReturn(null);

        assertThrows(GatewayException.class, () -> notifyChannelServiceImpl.delete(999L));
        verify(notifyChannelMapper, times(0)).deleteById(eq(999L));
    }

    @Test
    @DisplayName("delete 服务：id 为空时抛 400")
    void delete_nullId_throwsBadRequest() {
        assertThrows(GatewayException.class, () -> notifyChannelServiceImpl.delete(null));
    }

    // ===================== 1b) 引用完整性（T07-A 收尾补充） =====================

    @Test
    @DisplayName("delete 服务：被告警规则引用时拒绝删除，且不落库删除")
    void delete_referencedByRules_refuses() {
        NotifyChannel existing = new NotifyChannel();
        existing.setId(1L);
        existing.setChannelName("交易研发-企微机器人");
        when(notifyChannelMapper.selectById(1L)).thenReturn(existing);
        when(notifyChannelMapper.countRulesUsingChannel(1L)).thenReturn(5L);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> notifyChannelServiceImpl.delete(1L));
        assertTrue(ex.getMessage().contains("被 5 条告警规则引用"),
                "异常消息应带引用条数，实际=" + ex.getMessage());

        // 关键：拒绝时必须真的没有删掉（渠道仍在）
        verify(notifyChannelMapper, times(0)).deleteById(eq(1L));
    }

    @Test
    @DisplayName("delete 服务：无人引用时正常删除")
    void delete_unreferenced_deletes() {
        NotifyChannel existing = new NotifyChannel();
        existing.setId(7L);
        existing.setChannelName("临时渠道");
        when(notifyChannelMapper.selectById(7L)).thenReturn(existing);
        when(notifyChannelMapper.countRulesUsingChannel(7L)).thenReturn(0L);

        notifyChannelServiceImpl.delete(7L);

        verify(notifyChannelMapper, times(1)).deleteById(eq(7L));
    }

    @Test
    @DisplayName("引用检查必须用 FIND_IN_SET（防 LIKE '%1%' 误判 '11'/'21'）")
    void countRulesUsingChannel_usesFindInSet() throws NoSuchMethodException {
        Method m = NotifyChannelMapper.class.getMethod("countRulesUsingChannel", Long.class);
        Select select = m.getAnnotation(Select.class);
        assertNotNull(select, "countRulesUsingChannel 必须带 @Select");
        String sql = String.join(" ", select.value()).toUpperCase();
        assertTrue(sql.contains("FIND_IN_SET"),
                "必须用 FIND_IN_SET 精确匹配逗号分隔 id，实际 SQL=" + sql);
        assertFalse(sql.contains("LIKE"),
                "不允许用 LIKE '%id%' 匹配（会把 '11' 误判为包含 1），实际 SQL=" + sql);
    }

    // ===================== 2) 三个新增权限点（必须挂上，否则全员 403） =====================

    @Test
    @DisplayName("delete 标注 @RequirePerm notify_channel:delete（高危）")
    void delete_hasNotifyChannelDeletePerm() throws NoSuchMethodException {
        RequirePerm ann = NotifyChannelController.class.getMethod("delete", Long.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("notify_channel:delete", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("notify_channel test 标注 @RequirePerm notify_channel:test（高危，外发）")
    void notifyTest_hasNotifyChannelTestPerm() throws NoSuchMethodException {
        RequirePerm ann = NotifyChannelController.class.getMethod("test", Long.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("notify_channel:test", ann.value());
        assertTrue(ann.risk());
    }

    @Test
    @DisplayName("alarm_rule test 标注 @RequirePerm alarm_rule:test（高危，外发）")
    void alarmTest_hasAlarmRuleTestPerm() throws NoSuchMethodException {
        RequirePerm ann = AlarmRuleController.class.getMethod("test", Long.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann);
        assertEquals("alarm_rule:test", ann.value());
        assertTrue(ann.risk());
    }

    // ===================== 3) 既有 4 个已播种高危码不回归 =====================

    @Test
    @DisplayName("既有 4 个已播种高危码（create/manual）注解未被破坏")
    void existingHighRiskPermAnnotations_intact() throws NoSuchMethodException {
        RequirePerm alarmCreate = AlarmRuleController.class
                .getMethod("create", AlarmRule.class).getAnnotation(RequirePerm.class);
        assertNotNull(alarmCreate);
        assertEquals("alarm_rule:create", alarmCreate.value());
        assertTrue(alarmCreate.risk());

        RequirePerm notifyCreate = NotifyChannelController.class
                .getMethod("create", NotifyChannel.class).getAnnotation(RequirePerm.class);
        assertNotNull(notifyCreate);
        assertEquals("notify_channel:create", notifyCreate.value());
        assertTrue(notifyCreate.risk());

        RequirePerm blockCreate = BlockRuleController.class
                .getMethod("create", BlockRule.class).getAnnotation(RequirePerm.class);
        assertNotNull(blockCreate);
        assertEquals("block_rule:create", blockCreate.value());
        assertTrue(blockCreate.risk());

        RequirePerm blockManual = BlockRuleController.class
                .getMethod("manualBlock", Long.class, ManualBlockRequest.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(blockManual);
        assertEquals("block_rule:manual", blockManual.value());
        assertTrue(blockManual.risk());
    }

    // ===================== 4) 权限策略：读 / 改 / 启停 不加权限点 =====================

    @Test
    @DisplayName("list / detail / update / toggle 一律不加 @RequirePerm")
    void readAndUpdateEndpoints_haveNoPermAnnotation() throws NoSuchMethodException {
        // 告警规则：toggle 参数名为 status
        assertNull(AlarmRuleController.class.getMethod("list", Integer.class)
                .getAnnotation(RequirePerm.class));
        assertNull(AlarmRuleController.class.getMethod("detail", Long.class)
                .getAnnotation(RequirePerm.class));
        assertNull(AlarmRuleController.class.getMethod("update", Long.class, AlarmRule.class)
                .getAnnotation(RequirePerm.class));
        assertNull(AlarmRuleController.class.getMethod("toggle", Long.class, Integer.class)
                .getAnnotation(RequirePerm.class));

        // 阻断规则：toggle 参数名为 enabled（与告警域的 status 不同，此处只校验方法签名存在且无权限点）
        assertNull(BlockRuleController.class.getMethod("list")
                .getAnnotation(RequirePerm.class));
        assertNull(BlockRuleController.class.getMethod("detail", Long.class)
                .getAnnotation(RequirePerm.class));
        assertNull(BlockRuleController.class.getMethod("update", Long.class, BlockRule.class)
                .getAnnotation(RequirePerm.class));
        assertNull(BlockRuleController.class.getMethod("toggle", Long.class, Integer.class)
                .getAnnotation(RequirePerm.class));

        // 通知渠道：list / detail 仍不加；update 在 T08 已补 sys:notify:update（见下一个用例）
        assertNull(NotifyChannelController.class.getMethod("list", Integer.class)
                .getAnnotation(RequirePerm.class));
        assertNull(NotifyChannelController.class.getMethod("detail", Long.class)
                .getAnnotation(RequirePerm.class));
    }

    @Test
    @DisplayName("T08：update 补挂 @RequirePerm sys:notify:update（原为假保护）")
    void notifyChannelUpdate_hasSysNotifyUpdatePerm() throws NoSuchMethodException {
        RequirePerm ann = NotifyChannelController.class
                .getMethod("update", Long.class, NotifyChannel.class)
                .getAnnotation(RequirePerm.class);
        assertNotNull(ann, "T08 要求补 sys:notify:update，否则前端闸门是假保护");
        assertEquals("sys:notify:update", ann.value());
        assertTrue(ann.risk());
    }

    // ===================== 5) 18 个端点全部存在（路由形态核查） =====================

    @Test
    @DisplayName("三域 18 个端点方法签名全部存在（6 + 6 + 6）")
    void allEighteenEndpointsExist() throws NoSuchMethodException {
        Class<AlarmRuleController> a = AlarmRuleController.class;
        assertNotNull(a.getMethod("list", Integer.class));
        assertNotNull(a.getMethod("detail", Long.class));
        assertNotNull(a.getMethod("create", AlarmRule.class));
        assertNotNull(a.getMethod("update", Long.class, AlarmRule.class));
        assertNotNull(a.getMethod("toggle", Long.class, Integer.class));
        assertNotNull(a.getMethod("test", Long.class));

        Class<BlockRuleController> b = BlockRuleController.class;
        assertNotNull(b.getMethod("list"));
        assertNotNull(b.getMethod("detail", Long.class));
        assertNotNull(b.getMethod("create", BlockRule.class));
        assertNotNull(b.getMethod("update", Long.class, BlockRule.class));
        assertNotNull(b.getMethod("toggle", Long.class, Integer.class));
        assertNotNull(b.getMethod("manualBlock", Long.class, ManualBlockRequest.class));

        Class<NotifyChannelController> n = NotifyChannelController.class;
        assertNotNull(n.getMethod("list", Integer.class));
        assertNotNull(n.getMethod("detail", Long.class));
        assertNotNull(n.getMethod("create", NotifyChannel.class));
        assertNotNull(n.getMethod("update", Long.class, NotifyChannel.class));
        assertNotNull(n.getMethod("delete", Long.class));
        assertNotNull(n.getMethod("test", Long.class));
    }

    @Test
    @DisplayName("阻断规则 toggle 形参类型为 Integer（前端以 enabled 传参）")
    void blockRuleToggle_takesIntegerFlag() throws NoSuchMethodException {
        Method m = BlockRuleController.class.getMethod("toggle", Long.class, Integer.class);
        assertEquals(Integer.class, m.getParameterTypes()[1]);
    }
}
