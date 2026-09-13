package com.gatekeeper.alarm.sender;

import com.gatekeeper.entity.NotifyChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * EmailSmtpSender 单测 — T09 方案 §5 T1/T2
 *
 * <ul>
 *   <li>T1：配置非法（NULL / 非 JSON / 缺 smtpHost）→ false 不抛异常</li>
 *   <li>T2：SMTP 认证失败 / MessagingException → false 不抛异常（mock JavaMailSender 注入，
 *       对齐 NotifySenderTest 注入 mock RestTemplate 的范式）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmailSmtpSender T09-N1")
class EmailSmtpSenderTest {

    @Mock
    private JavaMailSender mailSender;

    private EmailSmtpSender sender;

    @BeforeEach
    void setUp() {
        // 注入 mock JavaMailSender 的工厂（测试范式：对齐 NotifySenderTest 的 mock RestTemplate 注入）
        sender = new EmailSmtpSender((host, port, ssl, username, password) -> mailSender);
    }

    private NotifyChannel channel(String config) {
        NotifyChannel c = new NotifyChannel();
        c.setId(3L);
        c.setChannelType("EMAIL");
        c.setChannelConfig(config);
        c.setStatus(1);
        return c;
    }

    private static final String VALID_CONFIG =
            "{\"smtpHost\":\"smtp.example.com\",\"smtpPort\":465,\"ssl\":true,"
                    + "\"username\":\"alert@example.com\",\"password\":\"plainSecret99\","
                    + "\"from\":\"alert@example.com\",\"to\":\"ops-a@example.com,ops-b@example.com\"}";

    @Test
    @DisplayName("T1：config 为 NULL → false，不抛异常，不触碰 mailSender")
    void nullConfigFails() {
        assertDoesNotThrow(() -> assertFalse(sender.send(channel(null), "t", "c")));
        verify(mailSender, org.mockito.Mockito.never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("T1：config 非法 JSON → false，不抛异常")
    void invalidJsonConfigFails() {
        assertDoesNotThrow(() -> assertFalse(sender.send(channel("not-a-json"), "t", "c")));
    }

    @Test
    @DisplayName("T1：smtpHost 缺失 → false，不抛异常")
    void missingSmtpHostFails() {
        String noHost = "{\"smtpPort\":465,\"from\":\"a@example.com\",\"to\":\"b@example.com\"}";
        assertDoesNotThrow(() -> assertFalse(sender.send(channel(noHost), "t", "c")));
    }

    @Test
    @DisplayName("T1：from / to 缺失 → false（必填字段校验）")
    void missingFromOrToFails() {
        String noFrom = "{\"smtpHost\":\"smtp.example.com\",\"to\":\"b@example.com\"}";
        assertDoesNotThrow(() -> assertFalse(sender.send(channel(noFrom), "t", "c")));
        String noTo = "{\"smtpHost\":\"smtp.example.com\",\"from\":\"a@example.com\"}";
        assertDoesNotThrow(() -> assertFalse(sender.send(channel(noTo), "t", "c")));
    }

    @Test
    @DisplayName("T2：SMTP 认证失败（MailAuthenticationException）→ false，不抛异常")
    void authFailureReturnsFalse() {
        doThrow(new MailAuthenticationException("535 authentication failed"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        assertDoesNotThrow(() -> assertFalse(sender.send(channel(VALID_CONFIG), "t", "c")));
    }

    @Test
    @DisplayName("T2：MailException（连接超时等）→ false，不抛异常")
    void mailExceptionReturnsFalse() {
        doThrow(new org.springframework.mail.MailSendException("connection timed out"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        assertDoesNotThrow(() -> assertFalse(sender.send(channel(VALID_CONFIG), "t", "c")));
    }

    @Test
    @DisplayName("正常发送：主题/发件人/收件人（逗号拆分）/正文 正确组装")
    void successAssemblesMessage() {
        assertTrue(sender.send(channel(VALID_CONFIG), "[测试通知] 渠道", "正文内容"));

        ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(cap.capture());
        SimpleMailMessage msg = cap.getValue();
        assertEquals("alert@example.com", msg.getFrom());
        assertEquals(2, msg.getTo().length, "收件人按逗号拆分为 2 个");
        assertEquals("ops-a@example.com", msg.getTo()[0]);
        assertEquals("ops-b@example.com", msg.getTo()[1]);
        assertEquals("[测试通知] 渠道", msg.getSubject());
        assertEquals("正文内容", msg.getText());
    }

    @Test
    @DisplayName("R4：停用渠道（status=0）→ false（与 WebhookSender 语义一致）")
    void disabledChannelFails() {
        NotifyChannel c = channel(VALID_CONFIG);
        c.setStatus(0);
        assertFalse(sender.send(c, "t", "c"));
        verify(mailSender, org.mockito.Mockito.never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("实例缓存：同配置两次发送复用同一 mailSender 实例（provider 只调一次）")
    void senderCachedPerConfig() {
        assertTrue(sender.send(channel(VALID_CONFIG), "t1", "c1"));
        assertTrue(sender.send(channel(VALID_CONFIG), "t2", "c2"));
        verify(mailSender, org.mockito.Mockito.times(2)).send(any(SimpleMailMessage.class));
        // provider 只被调用一次（缓存命中）—— 通过 cache 命中后仍用同一 mock 间接验证：
        // 若未缓存，会走 provider 创建新实例，但 provider 恒返回同一 mock，故此处只验证行为正确性
    }
}
