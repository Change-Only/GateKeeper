package com.gatekeeper.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * T-SEC-1 P0-5 / P1-1 回归：配置内置默认值的「生产拒绝、开发告警不阻断」。
 *
 * <p>用真实的 {@link MockEnvironment}（{@code ConfigurableEnvironment}）以便：
 * ① 属性源里保留原始占位符 {@code ${ENV:默认值}}，供「是否仍在用内置默认值」检测；
 * ② 可设置 active profile 切换生产/开发判定。</p>
 *
 * <p>可证伪：若把 {@code checkShippedDefault} 去掉，prod 用例应抛异常却不会抛 → 失败；
 * 若把生产判定去掉（改成无条件致命），dev 用例会抛异常 → 失败。</p>
 */
@DisplayName("SecurityStartupCheck：内置默认值检测（P0-5 / P1-1）")
class SecurityStartupCheckDefaultSecretsTest {

    private MockEnvironment environment;
    private SysConfigAccessor sysConfigAccessor;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        sysConfigAccessor = Mockito.mock(SysConfigAccessor.class);
        Mockito.when(sysConfigAccessor.getBoolean(anyString(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    /** 三项密钥都在用 yml 的内置默认值（占位符形式，等价「未注入环境变量」） */
    private void useBuiltInDefaults() {
        environment.withProperty("gatekeeper.jwt.secret",
                "${GATEKEEPER_JWT_SECRET:BuiltInJwtDefaultSecret1234567890abcdef}");
        environment.withProperty("gatekeeper.crypto.aes-key",
                "${GATEKEEPER_AES_KEY:BuiltInAesDefaultKey1234567890abcdefghij}");
        environment.withProperty("spring.datasource.password",
                "${GATEKEEPER_DB_PASSWORD:BuiltInDbPassword1234}");
    }

    private SecurityStartupCheck newCheck() {
        return new SecurityStartupCheck(environment, sysConfigAccessor);
    }

    @Test
    @DisplayName("生产环境 + 仍在用内置默认值 → 拒绝启动")
    void prod_withBuiltInDefaults_fails() {
        environment.setActiveProfiles("prod");
        useBuiltInDefaults();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> newCheck().check());
        assertTrue(ex.getMessage().contains("内置的默认值"), "应命中「内置默认值」拦截：" + ex.getMessage());
    }

    @Test
    @DisplayName("开发环境（无 prod profile）+ 内置默认值 → 仅告警，不阻断启动")
    void dev_withBuiltInDefaults_doesNotThrow() {
        useBuiltInDefaults(); // 不激活任何 profile = 开发环境

        assertDoesNotThrow(() -> newCheck().check(),
                "开发环境不应因内置默认值而无法启动（否则本地起不来）");
    }

    @Test
    @DisplayName("生产环境 + 已注入真实密钥 → 通过")
    void prod_withInjectedSecrets_passes() {
        environment.setActiveProfiles("prod");
        environment.withProperty("gatekeeper.jwt.secret", "InjectedJwtSecret-abcdefghijklmnopqrstuv");
        environment.withProperty("gatekeeper.crypto.aes-key", "InjectedAesKey-abcdefghijklmnopqrstuvwx");
        environment.withProperty("spring.datasource.password", "InjectedDbPassw0rd!");

        assertDoesNotThrow(() -> newCheck().check());
    }

    @Test
    @DisplayName("缺密钥在开发环境也致命（原有行为保持）")
    void dev_missingSecret_stillFatal() {
        environment.withProperty("gatekeeper.jwt.secret", "");
        environment.withProperty("gatekeeper.crypto.aes-key", "");
        environment.withProperty("spring.datasource.password", "");

        assertThrows(IllegalStateException.class, () -> newCheck().check());
    }

    @Test
    @DisplayName("数据库口令过短 → 拒绝启动（P1-1 新增最小长度）")
    void shortDbPassword_fails() {
        environment.withProperty("gatekeeper.jwt.secret", "InjectedJwtSecret-abcdefghijklmnopqrstuv");
        environment.withProperty("gatekeeper.crypto.aes-key", "InjectedAesKey-abcdefghijklmnopqrstuvwx");
        environment.withProperty("spring.datasource.password", "short");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> newCheck().check());
        assertTrue(ex.getMessage().contains("长度不足"));
    }
}
