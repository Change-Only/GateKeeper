package com.gatekeeper.alarm.sender;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.gatekeeper.entity.NotifyChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EMAIL 渠道 SMTP 真发器 — T09-N1 新增（替换旧桩发）
 *
 * <p>设计要点（方案 §3.2）：</p>
 * <ul>
 *   <li><strong>不使用</strong>全局 {@code spring.mail.*} 配置 —— 每渠道 SMTP 各异，
 *       配置存于 {@code channel_config}（§2.2 冻结 Schema），按渠道动态构造
 *       {@link org.springframework.mail.javamail.JavaMailSenderImpl}。</li>
 *   <li>按 {@code host:port:user:password} 维度缓存实例，防每次发送重建连接池。</li>
 *   <li>超时：连接 5s / 读 10s，防 SMTP 黑洞拖垮 10s 周期的告警评估线程
 *       （对齐网关 default-timeout 量级）。</li>
 *   <li>SSL：{@code ssl=true}（465 端口）走 SMTPS；否则 STARTTLS 机会式协商（25/587）。</li>
 *   <li>收件人 {@code to} 逗号分隔；title 为主题、content 为正文。</li>
 *   <li><strong>任何异常 catch → false</strong>（对齐「绝不抛给调用方」铁律）。</li>
 * </ul>
 *
 * <p>可测试性：{@link MailSenderProvider} 函数式注入点，单测可注入 mock
 * {@link JavaMailSender}（对齐 NotifySenderTest 注入 mock RestTemplate 的范式）。</p>
 *
 * @author GateKeeper
 * @since T09-N1 (APIM V2)
 */
@Slf4j
@Component
public class EmailSmtpSender implements ChannelSender {

    /** SMTP 连接超时（毫秒） */
    private static final String CONNECT_TIMEOUT_MS = "5000";

    /** SMTP 读/写超时（毫秒） */
    private static final String IO_TIMEOUT_MS = "10000";

    /** 实例缓存：同 host:port:user:password 复用，防每次发送重建 */
    private final Map<String, JavaMailSender> senderCache = new ConcurrentHashMap<>();

    /** mail sender 工厂（测试注入 mock 用） */
    private final MailSenderProvider provider;

    /**
     * 默认构造（生产环境 Spring 实例化）：使用真实 JavaMailSenderImpl 工厂。
     */
    public EmailSmtpSender() {
        this(EmailSmtpSender::createMailSender);
    }

    /**
     * 注入工厂的构造（主要用于单元测试注入 mock JavaMailSender）。
     *
     * @param provider JavaMailSender 工厂
     */
    public EmailSmtpSender(MailSenderProvider provider) {
        this.provider = provider;
    }

    @Override
    public String[] supportTypes() {
        return new String[]{"EMAIL"};
    }

    @Override
    public boolean send(NotifyChannel channel, String title, String content) {
        if (channel == null) {
            return false;
        }
        try {
            JSONObject cfg = parseConfig(channel.getChannelConfig());
            if (cfg == null) {
                log.warn("channel {} config is empty or invalid JSON, skip", channel.getId());
                return false;
            }
            String host = cfg.getStr("smtpHost", cfg.getStr("host"));
            Integer port = cfg.getInt("smtpPort", cfg.getInt("port"));
            Boolean ssl = cfg.getBool("ssl", Boolean.FALSE);
            String username = cfg.getStr("username");
            String password = cfg.getStr("password");
            String from = cfg.getStr("from");
            String to = cfg.getStr("to");

            if (!StringUtils.hasText(host)) {
                log.warn("channel {} missing smtpHost, skip", channel.getId());
                return false;
            }
            if (!StringUtils.hasText(from)) {
                log.warn("channel {} missing from address, skip", channel.getId());
                return false;
            }
            if (!StringUtils.hasText(to)) {
                log.warn("channel {} missing recipients(to), skip", channel.getId());
                return false;
            }
            boolean useSsl = ssl != null && ssl;
            int usePort = port == null ? (useSsl ? 465 : 25) : port;

            // 停用渠道：与 WebhookSender 的 R4 语义一致 —— 未发送即失败
            if (channel.getStatus() != null && channel.getStatus() == 0) {
                log.warn("channel {} is disabled, skip send", channel.getId());
                return false;
            }

            JavaMailSender mailSender = senderCache.computeIfAbsent(
                    cacheKey(host, usePort, username, password),
                    k -> provider.create(host, usePort, useSsl, username, password));

            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(to.split("\\s*,\\s*"));
            msg.setSubject(title == null ? "" : title);
            msg.setText(content == null ? "" : content);
            mailSender.send(msg);
            log.info("channel {} email sent via {}:{} to {} recipients",
                    channel.getId(), host, usePort, msg.getTo() == null ? 0 : msg.getTo().length);
            return true;
        } catch (RuntimeException e) {
            // T2：SMTP 认证失败(MailAuthenticationException)/连接失败(MailSendException)等
            // 一律 catch → false，绝不抛给调用方。MailException 本身是 RuntimeException
            // 子类，故单 catch RuntimeException 即全覆盖。
            log.error("channel {} email send failed: {}", channel.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 构造带超时与 SSL 配置的 JavaMailSenderImpl。
     */
    static JavaMailSender createMailSender(String host, int port, boolean ssl,
                                           String username, String password) {
        org.springframework.mail.javamail.JavaMailSenderImpl sender =
                new org.springframework.mail.javamail.JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        if (StringUtils.hasText(username)) {
            sender.setUsername(username);
        }
        if (StringUtils.hasText(password)) {
            sender.setPassword(password);
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.connectiontimeout", CONNECT_TIMEOUT_MS);
        props.put("mail.smtp.timeout", IO_TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", IO_TIMEOUT_MS);
        if (ssl) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            // 587/25：STARTTLS 机会式协商（服务端支持则升级，不支持则明文）
            props.put("mail.smtp.starttls.enable", "true");
        }
        props.put("mail.smtp.auth", String.valueOf(StringUtils.hasText(username)));
        props.put("mail.debug", "false");
        return sender;
    }

    private String cacheKey(String host, int port, String username, String password) {
        return host + "|" + port + "|" + (username == null ? "" : username)
                + "|" + (password == null ? "" : password);
    }

    /**
     * 解析渠道配置 JSON（容错：非法 JSON 返回 null）。
     */
    private JSONObject parseConfig(String config) {
        if (!StringUtils.hasText(config)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(config);
        } catch (Exception e) {
            log.warn("parse channelConfig failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * JavaMailSender 工厂（函数式接口，测试注入 mock 用）。
     */
    @FunctionalInterface
    public interface MailSenderProvider {

        /**
         * 按渠道 SMTP 配置构造（或返回 mock 的）JavaMailSender。
         */
        JavaMailSender create(String host, int port, boolean ssl, String username, String password);
    }
}
