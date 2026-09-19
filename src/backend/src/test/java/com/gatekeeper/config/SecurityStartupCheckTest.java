package com.gatekeeper.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * SecurityStartupCheck 单元测试：验证激进密钥策略的各项拦截规则
 */
@DisplayName("安全密钥启动自检")
class SecurityStartupCheckTest {

    private Environment environment;
    private SysConfigAccessor sysConfigAccessor;
    private SecurityStartupCheck check;

    /** 32 位合法密钥样例 */
    private static final String VALID_SECRET = "a-very-long-random-secret-key-32b!";

    @BeforeEach
    void setUp() {
        environment = Mockito.mock(Environment.class);
        // T19：危险开关巡检依赖配置读取器；默认视为「配置缺失 ⇒ 开关均开启」
        sysConfigAccessor = Mockito.mock(SysConfigAccessor.class);
        Mockito.when(sysConfigAccessor.getBoolean(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));
        check = new SecurityStartupCheck(environment, sysConfigAccessor);
        // 默认返回空串（与 yml 的空默认值一致），单个用例按需覆盖
        when(environment.getProperty(Mockito.anyString(), Mockito.anyString())).thenReturn("");
    }

    private void stub(String key, String value) {
        when(environment.getProperty(key, "")).thenReturn(value);
    }

    @Test
    @DisplayName("三项密钥全部合规时正常通过")
    void check_allValid_passes() {
        stub("gatekeeper.jwt.secret", VALID_SECRET);
        stub("gatekeeper.crypto.aes-key", VALID_SECRET);
        stub("spring.datasource.password", "Strong#Passw0rd!2026");

        assertDoesNotThrow(() -> check.check());
    }

    @Test
    @DisplayName("缺失 JWT 密钥时拒绝启动并给出环境变量指引")
    void check_missingJwtSecret_fails() {
        stub("gatekeeper.crypto.aes-key", VALID_SECRET);
        stub("spring.datasource.password", "Strong#Passw0rd!2026");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check.check());
        assertTrue(ex.getMessage().contains("GATEKEEPER_JWT_SECRET"));
    }

    @Test
    @DisplayName("AES 密钥过短时拒绝启动")
    void check_shortAesKey_fails() {
        stub("gatekeeper.jwt.secret", VALID_SECRET);
        stub("gatekeeper.crypto.aes-key", "short-key");
        stub("spring.datasource.password", "Strong#Passw0rd!2026");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check.check());
        assertTrue(ex.getMessage().contains("长度不足"));
    }

    @Test
    @DisplayName("使用历史默认密钥时拒绝启动")
    void check_legacyDefaultSecret_fails() {
        stub("gatekeeper.jwt.secret", "GateKeeperJwtSecret2026@Secure#Key");
        stub("gatekeeper.crypto.aes-key", VALID_SECRET);
        stub("spring.datasource.password", "Strong#Passw0rd!2026");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check.check());
        assertTrue(ex.getMessage().contains("历史默认值"));
    }

    @Test
    @DisplayName("数据库密码为弱口令时拒绝启动")
    void check_weakDbPassword_fails() {
        stub("gatekeeper.jwt.secret", VALID_SECRET);
        stub("gatekeeper.crypto.aes-key", VALID_SECRET);
        stub("spring.datasource.password", "123456");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check.check());
        assertTrue(ex.getMessage().contains("GATEKEEPER_DB_PASSWORD"));
    }

    @Test
    @DisplayName("缺失数据库密码时拒绝启动")
    void check_missingDbPassword_fails() {
        stub("gatekeeper.jwt.secret", VALID_SECRET);
        stub("gatekeeper.crypto.aes-key", VALID_SECRET);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> check.check());
        assertTrue(ex.getMessage().contains("GATEKEEPER_DB_PASSWORD"));
    }
}
