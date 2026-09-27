package com.gatekeeper.security;

import com.gatekeeper.alarm.AlarmRuleController;
import com.gatekeeper.block.BlockRuleController;
import com.gatekeeper.controller.AlertController;
import com.gatekeeper.controller.CallLogController;
import com.gatekeeper.controller.EncryptionConfigController;
import com.gatekeeper.entity.AlarmRule;
import com.gatekeeper.entity.ApiEncryptionConfig;
import com.gatekeeper.entity.AppEncryptionConfig;
import com.gatekeeper.entity.BlockRule;
import com.gatekeeper.grant.GrantController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-SEC-1 P0 权限注解回归测试。
 *
 * <p>逐端点用反射断言 {@code @RequirePerm} 的存在与取值（P0-2 / P0-3 / P0-4 / P0-6）。
 * 这些断言<b>可证伪</b>：把任一注解删掉，对应测试立即失败。</p>
 *
 * <p>所复用权限码均为 {@code sys_menu} 中<b>已播种</b>的既有码（零新增）：
 * {@code grant:approve} / {@code block_rule:create} / {@code alarm_rule:create} / {@code api:env:update} /
 * {@code alarm:handle} / {@code log:call:list} / {@code log:call:detail} / {@code audit:export}。</p>
 */
@DisplayName("P0 安全修复：写/读端点权限注解")
class P0SecurityAnnotationTest {

    private static RequirePerm ann(Class<?> clazz, String method, Class<?>... params) throws Exception {
        Method m = clazz.getMethod(method, params);
        return m.getAnnotation(RequirePerm.class);
    }

    private static void assertPerm(Class<?> clazz, String method, String expectedCode, boolean expectedRisk,
                                   Class<?>... params) throws Exception {
        RequirePerm a = ann(clazz, method, params);
        assertNotNull(a, clazz.getSimpleName() + "#" + method + " 必须标注 @RequirePerm");
        assertEquals(expectedCode, a.value(), clazz.getSimpleName() + "#" + method + " permCode");
        assertEquals(expectedRisk, a.risk(), clazz.getSimpleName() + "#" + method + " risk");
    }

    // ===================== P0-2：延期授权绕过到期 =====================

    @Test
    @DisplayName("P0-2：GrantController#renew 标注 grant:approve（高危）")
    void grantRenew_annotated() throws Exception {
        assertPerm(GrantController.class, "renew", "grant:approve", true,
                Long.class, GrantController.GrantRenewRequest.class);
    }

    // ===================== P0-3：安全防护可被静默注销 =====================

    @Test
    @DisplayName("P0-3：BlockRuleController#update / #toggle 标注 block_rule:create（高危）")
    void blockRuleUpdateToggle_annotated() throws Exception {
        assertPerm(BlockRuleController.class, "update", "block_rule:create", true, Long.class, BlockRule.class);
        assertPerm(BlockRuleController.class, "toggle", "block_rule:create", true, Long.class, Integer.class);
    }

    @Test
    @DisplayName("P0-3：AlarmRuleController#update / #toggle 标注 alarm_rule:create（高危）")
    void alarmRuleUpdateToggle_annotated() throws Exception {
        assertPerm(AlarmRuleController.class, "update", "alarm_rule:create", true, Long.class, AlarmRule.class);
        assertPerm(AlarmRuleController.class, "toggle", "alarm_rule:create", true, Long.class, Integer.class);
    }

    @Test
    @DisplayName("P0-3：AlertController#markRead / #markAllRead 标注 alarm:handle")
    void alertRead_annotated() throws Exception {
        assertPerm(AlertController.class, "markRead", "alarm:handle", false, Long.class);
        assertPerm(AlertController.class, "markAllRead", "alarm:handle", false);
    }

    // ===================== P0-4：加解密配置可被改写 =====================

    @Test
    @DisplayName("P0-4：EncryptionConfigController#saveInterfaceConfig / #saveAppConfig 标注 api:env:update")
    void encryptionConfigWrites_annotated() throws Exception {
        assertPerm(EncryptionConfigController.class, "saveInterfaceConfig", "api:env:update", true,
                ApiEncryptionConfig.class);
        assertPerm(EncryptionConfigController.class, "saveAppConfig", "api:env:update", true,
                AppEncryptionConfig.class);
    }

    // ===================== P0-6：全量调用报文可被读取导出 =====================

    @Test
    @DisplayName("P0-6：CallLogController 四端点补权限（list/detail/export-tasks/download）")
    void callLogEndpoints_annotated() throws Exception {
        assertPerm(CallLogController.class, "list", "log:call:list", false,
                int.class, int.class, Long.class, Long.class, Integer.class, String.class,
                Boolean.class, Boolean.class, LocalDateTime.class, LocalDateTime.class);
        assertPerm(CallLogController.class, "detail", "log:call:detail", false, Long.class);
        assertPerm(CallLogController.class, "tasks", "audit:export", true, int.class, int.class, String.class);
        assertPerm(CallLogController.class, "download", "audit:export", true, Long.class, String.class);
    }

    // ===================== 防回归：加解密 Controller 的只读端点不被误加注解 =====================

    @Test
    @DisplayName("P0-4：只读 GET 端点保持无注解（读侧策略不变）")
    void encryptionReadEndpoints_haveNoAnnotation() throws Exception {
        assertFalse(isAnnotated(EncryptionConfigController.class, "getInterfaceConfig", Long.class),
                "只读端点不应加注解（读侧本轮不收敛）");
        assertFalse(isAnnotated(EncryptionConfigController.class, "getAppConfig", Long.class),
                "只读端点不应加注解（读侧本轮不收敛）");
    }

    private static boolean isAnnotated(Class<?> clazz, String method, Class<?>... params) throws Exception {
        return clazz.getMethod(method, params).getAnnotation(RequirePerm.class) != null;
    }

    @Test
    @DisplayName("P0-4：EncryptionConfigController 写端点均非空 value（防 P2-9 空值埋雷）")
    void encryptionWriteEndpoints_haveNonEmptyCode() throws Exception {
        assertTrue(ann(EncryptionConfigController.class, "saveInterfaceConfig", ApiEncryptionConfig.class)
                .value().length() > 0);
        assertTrue(ann(EncryptionConfigController.class, "saveAppConfig", AppEncryptionConfig.class)
                .value().length() > 0);
    }
}
