package com.gatekeeper.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 安全密钥启动自检（激进策略）
 *
 * <p>历史版本曾把 JWT/AES/数据库密码的默认值写进配置文件并提交到仓库，
 * 任何拿到仓库的人都可以用默认密钥伪造管理员令牌。现在改为：
 * 配置文件不再携带任何真实默认值（缺省为空串），由本类在 Bean 初始化阶段
 * 统一校验，只要出现「缺失 / 过短 / 等于历史默认值」任一情况，
 * 直接抛异常终止启动（fail-fast），绝不让弱配置的服务对外提供服务。</p>
 *
 * <p>密钥通过环境变量注入（Docker 部署见 docker-compose.yml / .env.example）：
 * <ul>
 *   <li>GATEKEEPER_JWT_SECRET —— JWT 签名密钥，长度 ≥ 32</li>
 *   <li>GATEKEEPER_AES_KEY —— 数据库密钥加密密钥，长度 ≥ 32</li>
 *   <li>GATEKEEPER_DB_PASSWORD —— MySQL 密码，非空</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityStartupCheck implements InitializingBean {

    /** 历史版本曾经写进仓库的默认密钥，永久拉黑：出现即拒绝启动 */
    private static final Set<String> FORBIDDEN_SECRETS = new HashSet<>(Arrays.asList(
            "GateKeeperJwtSecret2026@Secure#Key",   // 旧版 JWT 默认密钥
            "GateKeeper2026SecretKeyABCDEFGHI",     // 旧版 AES 默认密钥
            "Yzxyzx@1234",                          // 旧版数据库默认密码
            "123456", "root", "admin", "password"   // 常见弱口令
    ));

    /** 密钥类配置的最小长度（字节） */
    private static final int MIN_SECRET_LENGTH = 32;

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
              .append("    GATEKEEPER_DB_PASSWORD=<数据库密码>\n")
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
     * 校验密钥类配置：非空、长度达标、不在黑名单
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
        }
    }

    /**
     * 校验数据库密码：非空且不在黑名单
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
        }
    }
}
