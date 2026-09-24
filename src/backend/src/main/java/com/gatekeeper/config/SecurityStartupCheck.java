package com.gatekeeper.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全密钥启动自检（激进策略）
 *
 * <p>密钥通过环境变量注入（Docker 部署见 docker-compose.yml / .env.example）：
 * <ul>
 *   <li>GATEKEEPER_JWT_SECRET —— JWT 签名密钥，长度 ≥ 32</li>
 *   <li>GATEKEEPER_AES_KEY —— 数据库密钥加密密钥，长度 ≥ 32</li>
 *   <li>GATEKEEPER_DB_PASSWORD —— MySQL 密码，长度 ≥ 12</li>
 * </ul></p>
 *
 * <h3>两级校验（P0-5 / P1-1 修复，2026-09-19 安全审计 T-SEC-1）</h3>
 * <ol>
 *   <li><b>历史默认值 / 弱口令（无条件致命）</b>：{@link #FORBIDDEN_SECRETS} 里的值曾随仓库公开分发，
 *       任何环境出现即拒绝启动。</li>
 *   <li><b>「仍在使用配置文件内置默认值」（生产环境致命，开发/测试仅告警）</b>：
 *       若解析出的密钥恰等于 {@code application.yml} 中 {@code ${ENV:默认值}} 的内置默认值，
 *       说明<b>没有通过环境变量注入</b>。生产环境（active profile 含 {@code prod}/{@code production}）
 *       直接拒绝启动；开发/测试环境仅打 ERROR 日志、不阻断（否则本地不带环境变量将无法启动服务）。</li>
 * </ol>
 *
 * <p><b>为何不把当前默认值硬编码进本类</b>：{@code application.yml} 未被 git 跟踪
 * （{@code .gitignore:41}），其内置默认值是本地/开发配置；把它们的字面量抄进本类会把密钥
 * <b>重新写进被跟踪的源码</b>，等于把 P0-5「密钥不入库」的修复又推翻。故 {@link #shippedDefaultOf(String)}
 * 改为<b>从属性源里解析原始占位符</b>取默认值 —— 默认值的唯一来源始终是那份未入库的 yml。</p>
 *
 * <p><b>已知限制（如实声明）</b>：本类只能做到「启动自检拦截」。真正的彻底修复是
 * <b>轮换 KEK + 全库密文重加密</b>（运维动作），不在代码改动能覆盖的范围内。
 * 另外，若生产部署既未激活 {@code prod} profile、又未注入环境变量，本类的第 2 项不会拦（因为无法
 * 判定其为生产）—— 部署侧应显式设置 {@code SPRING_PROFILES_ACTIVE=prod} 或直接注入密钥。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityStartupCheck implements InitializingBean {

    /** 历史版本曾经写进仓库的默认密钥 + 常见弱口令，永久拉黑：出现即拒绝启动 */
    private static final Set<String> FORBIDDEN_SECRETS = new HashSet<>(Arrays.asList(
            "GateKeeperJwtSecret2026@Secure#Key",   // 旧版 JWT 默认密钥
            "GateKeeper2026SecretKeyABCDEFGHI",     // 旧版 AES 默认密钥
            "Yzxyzx@1234",                          // 旧版数据库默认密码
            "123456", "root", "admin", "password"   // 常见弱口令
    ));

    /** 密钥类配置的最小长度（字节） */
    private static final int MIN_SECRET_LENGTH = 32;

    /** 数据库口令的最小长度（P1-1：原先只校验非空 + 黑名单，弱口令可过） */
    private static final int MIN_DB_PASSWORD_LENGTH = 12;

    /**
     * 匹配 yml 属性值里的占位符 {@code "${ENV_VAR:默认值}"}，用于识别「未注入环境变量、仍在使用内置默认值」。
     * 形如 {@code ${GATEKEEPER_AES_KEY:xxx}} 的值会解析出默认值 {@code xxx}。
     */
    private static final Pattern PLACEHOLDER_WITH_DEFAULT = Pattern.compile("^\\$\\{[^:}]+:(.*)}$");

    private final Environment environment;

    /**
     * 系统参数读取器（T19 新增）—— 仅用于「危险开关巡检」。
     *
     * <p>它读的是 DB 里的 {@code sys_config}，而 {@link SysConfigAccessor} 自身 fail-open
     * （读不到就用默认值 true），因此本类不会因为数据库尚未就绪而启动失败。</p>
     */
    private final SysConfigAccessor sysConfigAccessor;

    /**
     * Bean 初始化阶段执行校验：先于 Web 端口开启，配置不达标则整个应用启动失败
     */
    @Override
    public void afterPropertiesSet() {
        check();
    }

    /**
     * 执行启动校验：任一项不达标立即终止启动
     *
     * @throws IllegalStateException 配置缺失或不安全时抛出，携带全部问题与修复指引
     */
    public void check() {
        List<String> problems = new ArrayList<>();

        checkSecret("gatekeeper.jwt.secret", "GATEKEEPER_JWT_SECRET", problems);
        checkSecret("gatekeeper.crypto.aes-key", "GATEKEEPER_AES_KEY", problems);
        checkDbPassword(problems);

        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n==================== 安全配置自检失败，服务拒绝启动 ====================\n");
            for (String p : problems) {
                sb.append("  [X] ").append(p).append('\n');
            }
            sb.append("----------------------------------------------------------------------\n")
              .append("  修复方式：通过环境变量注入安全密钥后重启，例如 Docker 部署时在 .env 中配置：\n")
              .append("    GATEKEEPER_JWT_SECRET=<至少32位随机串>\n")
              .append("    GATEKEEPER_AES_KEY=<至少32位随机串>\n")
              .append("    GATEKEEPER_DB_PASSWORD=<至少12位数据库密码>\n")
              .append("  生成随机密钥示例：openssl rand -base64 32\n")
              .append("====================================================================");
            throw new IllegalStateException(sb.toString());
        }
        log.info("Security startup check passed: jwt/aes/db secrets are properly configured");

        // T19：危险开关巡检（不阻断启动，只留痕）
        warnOnUnsafeSwitches();
    }

    /**
     * 危险开关巡检 —— 检查 {@code sys_config} 里两个「应急逃生口」是否被遗留在关闭状态。
     *
     * <p><b>刻意只记 ERROR 日志、不阻断启动</b>：这两个开关的语义就是「应急临时关闭」，
     * 若因此拒绝启动，运维在最需要重启的时刻反而起不来服务，与设计意图相悖。
     * 但它必须显眼 —— 所以用 ERROR 级别，且每次启动都会重新告警（不会像请求侧那样只打一次）。</p>
     *
     * <p>整段包在 try/catch 里：配置读取本身已 fail-open（返回默认 true，即「视为已开启」），
     * 这里再兜一层，确保任何异常都不可能影响启动流程。</p>
     */
    private void warnOnUnsafeSwitches() {
        try {
            if (!sysConfigAccessor.getBoolean(SysConfigAccessor.KEY_GATEWAY_AUTH_ENABLED, true)) {
                log.error("[安全告警] 网关签名校验处于关闭状态（sys_config.gateway.auth.enabled=false）："
                        + "防伪造/防重放已失效，任何持有 AppKey 的调用方都能通行。若为应急临时关闭，请尽快改回 true");
            }
            if (!sysConfigAccessor.getBoolean(SysConfigAccessor.KEY_GATEWAY_RATELIMIT_ENABLED, true)) {
                log.error("[安全告警] 网关限流处于关闭状态（sys_config.gateway.ratelimit.enabled=false）："
                        + "QPS/并发/日配额全部失效。若为应急临时关闭，请尽快改回 true");
            }
        } catch (Exception e) {
            log.warn("危险开关巡检跳过（不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * 校验密钥类配置：非空、长度达标、不在黑名单、非「内置默认值」
     *
     * @param propertyKey 配置键（如 gatekeeper.jwt.secret）
     * @param envVarName  对应环境变量名（用于报错指引）
     * @param problems    问题收集列表
     */
    private void checkSecret(String propertyKey, String envVarName, List<String> problems) {
        String value = environment.getProperty(propertyKey, "");
        if (value.isEmpty()) {
            problems.add("缺少 " + propertyKey + "：请设置环境变量 " + envVarName);
            return;
        }
        if (value.length() < MIN_SECRET_LENGTH) {
            problems.add(propertyKey + " 长度不足：当前 " + value.length()
                    + " 位，要求至少 " + MIN_SECRET_LENGTH + " 位（设置 " + envVarName + "）");
            return;
        }
        if (FORBIDDEN_SECRETS.contains(value)) {
            problems.add(propertyKey + " 使用了历史默认值或弱口令，禁止启动（设置新的 " + envVarName + "）");
            return;
        }
        checkShippedDefault(propertyKey, envVarName, value, problems);
    }

    /**
     * 校验数据库密码：非空、长度达标、不在黑名单、非「内置默认值」
     *
     * @param problems 问题收集列表
     */
    private void checkDbPassword(List<String> problems) {
        String value = environment.getProperty("spring.datasource.password", "");
        if (value.isEmpty()) {
            problems.add("缺少数据库密码：请设置环境变量 GATEKEEPER_DB_PASSWORD");
            return;
        }
        if (FORBIDDEN_SECRETS.contains(value)) {
            problems.add("数据库密码为历史默认值或弱口令，禁止启动（设置新的 GATEKEEPER_DB_PASSWORD）");
            return;
        }
        if (value.length() < MIN_DB_PASSWORD_LENGTH) {
            problems.add("数据库密码长度不足：当前 " + value.length()
                    + " 位，要求至少 " + MIN_DB_PASSWORD_LENGTH + " 位（设置 GATEKEEPER_DB_PASSWORD）");
            return;
        }
        checkShippedDefault("spring.datasource.password", "GATEKEEPER_DB_PASSWORD", value, problems);
    }

    /**
     * 「仍在使用配置文件内置默认值」检测（P0-5 / P1-1）。
     *
     * <p>若解析出的值恰等于本属性在配置里的内置默认值，说明未通过环境变量注入：
     * 生产环境计入致命问题，开发/测试环境仅记 ERROR 日志而不阻断启动。</p>
     *
     * @param propertyKey 配置键
     * @param envVarName  对应环境变量名（用于报错指引）
     * @param value       当前解析出的值
     * @param problems    致命问题收集列表（仅生产环境追加）
     */
    private void checkShippedDefault(String propertyKey, String envVarName, String value, List<String> problems) {
        String shipped = shippedDefaultOf(propertyKey);
        if (shipped == null || shipped.isEmpty() || !shipped.equals(value)) {
            return;
        }
        String msg = propertyKey + " 仍在使用配置文件内置的默认值（未通过环境变量 " + envVarName + " 注入）";
        if (isProduction()) {
            problems.add(msg + "，禁止在生产环境启动");
        } else {
            log.error("[安全告警] {}；当前非生产环境（active profile 不含 prod），仅告警不阻断启动", msg);
        }
    }

    /**
     * 从属性源里解析某配置项占位符的内置默认值；取不到（或值不是占位符）返回 {@code null}。
     *
     * <p>只读<b>原始</b>属性值（如 {@code "${GATEKEEPER_AES_KEY:Gk9#..."}），因此不依赖任何硬编码字面量。</p>
     *
     * @param propertyKey 配置键
     * @return 内置默认值；无法解析时为 {@code null}
     */
    private String shippedDefaultOf(String propertyKey) {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return null;
        }
        for (PropertySource<?> ps : ((ConfigurableEnvironment) environment).getPropertySources()) {
            Object raw = ps.getProperty(propertyKey);
            if (!(raw instanceof String)) {
                continue;
            }
            Matcher m = PLACEHOLDER_WITH_DEFAULT.matcher(((String) raw).trim());
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * 是否为生产环境：active profile 含 {@code prod} 或 {@code production}。
     *
     * <p>任何解析异常一律按开发环境处理（不因自检自身出错而阻断启动）。</p>
     *
     * @return true=生产环境
     */
    private boolean isProduction() {
        if (!(environment instanceof ConfigurableEnvironment)) {
            return false;
        }
        try {
            return environment.acceptsProfiles(Profiles.of("prod", "production"));
        } catch (Exception e) {
            return false;
        }
    }
}
