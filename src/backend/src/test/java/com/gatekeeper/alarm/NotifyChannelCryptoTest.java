package com.gatekeeper.alarm;

import cn.hutool.json.JSONUtil;
import com.gatekeeper.alarm.impl.NotifyChannelServiceImpl;
import com.gatekeeper.entity.NotifyChannel;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.NotifyChannelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NotifyChannelServiceImpl 配置加密链路单测 — T09-N1（方案 §5-T5/T6/T7）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li><strong>T5 写路径 FAIL-CLOSED</strong>：create 落库前 password 加密为 {@code enc:}
 *       密文；加密失败抛业务异常且<strong>不落库</strong>（绝不明文落库）。</li>
 *   <li><strong>T6 掩码回写防线</strong>：update 传入掩码格式 password（前端编辑回显原样回传）
 *       时保留库中原值；传新明文则正常加密。</li>
 *   <li><strong>T7 传输脱敏</strong>：create/get/list 响应中 password 均为掩码，
 *       不含明文也不含密文。</li>
 *   <li><strong>读路径</strong>：listByIds（发送用）解密 {@code enc:} 密文；
 *       test() 发送用解密副本、回写仍为密文。</li>
 *   <li><strong>R2 超长防护</strong>：密文配置超 1024 字符 → 明确 badRequest 而非静默截断。</li>
 * </ul>
 *
 * <p>写法参照 {@code NotifyChannelDeleteServiceTest}：ServiceImpl 的 {@code baseMapper}
 * 为 protected 字段，反射注入 mock；aesDbKey 用 ReflectionTestUtils 注入。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotifyChannelService 配置加密链路 T09-N1")
class NotifyChannelCryptoTest {

    private static final String AES_KEY = "TEST_32_BYTES_LONG_KEY_FOR_AES_256";

    private static final String FAKE_CIPHER = "Q0lQSEVSMTIzNDU2Nzg5MGFiY2RlZg==";

    @Mock
    private NotifyChannelMapper notifyChannelMapper;

    @Mock
    private NotifySender notifySender;

    @Mock
    private com.gatekeeper.crypto.CryptoService cryptoService;

    private NotifyChannelServiceImpl service;

    /** create/update 落库瞬间的 channelConfig 快照（避免响应脱敏就地修改污染断言） */
    private final AtomicReference<String> persistedConfig = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new NotifyChannelServiceImpl(notifySender, cryptoService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "aesDbKey", AES_KEY);
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(service, notifyChannelMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 默认桩：AES-256 ECB 加密返回固定密文
        when(cryptoService.encrypt(eq("AES"), anyString(), anyString(),
                isNull(), eq("ECB"), eq("PKCS5Padding")))
                .thenReturn(FAKE_CIPHER);
        when(cryptoService.decrypt(eq("AES"), eq(FAKE_CIPHER), anyString(),
                isNull(), eq("ECB"), eq("PKCS5Padding")))
                .thenReturn("secret-pwd-123");
        // 落库瞬间抓快照：insert / updateById 收到的是尚未被响应脱敏改写的实体
        persistedConfig.set(null);
        when(notifyChannelMapper.insert(any(NotifyChannel.class))).thenAnswer(inv -> {
            persistedConfig.set(inv.getArgument(0, NotifyChannel.class).getChannelConfig());
            return 1;
        });
        when(notifyChannelMapper.updateById(any(NotifyChannel.class))).thenAnswer(inv -> {
            persistedConfig.set(inv.getArgument(0, NotifyChannel.class).getChannelConfig());
            return 1;
        });
    }

    private static String passwordOf(String config) {
        return JSONUtil.parseObj(config).getStr("password");
    }

    private NotifyChannel newChannel(String type, String config) {
        NotifyChannel c = new NotifyChannel();
        c.setChannelName("测试渠道");
        c.setChannelType(type);
        c.setChannelConfig(config);
        c.setStatus(1);
        return c;
    }

    // ============================== T5 写路径 ==============================

    @Test
    @DisplayName("T5：create 落库前 password 加密为 enc: 密文（落库瞬间抓快照）")
    void create_persistsEncryptedPassword() {
        NotifyChannel c = newChannel("EMAIL",
                "{\"smtpHost\":\"smtp.example.com\",\"from\":\"a@b.c\",\"password\":\"secret-pwd-123\"}");
        NotifyChannel created = service.create(c);

        // 落库瞬间：密文带 enc: 前缀，绝不明文
        String persisted = persistedConfig.get();
        assertTrue(persisted != null && passwordOf(persisted).startsWith("enc:"),
                "落库的 password 应为 enc: 密文，实际: " + persisted);
        assertEquals("enc:" + FAKE_CIPHER, passwordOf(persisted));
        assertNotEquals("secret-pwd-123", passwordOf(persisted));

        // 返回给前端：掩码（对密文做掩码 —— 非明文、非密文、不可逆推）
        assertEquals("enc:****Zg==", passwordOf(created.getChannelConfig()));
        assertFalse(created.getChannelConfig().contains("secret-pwd-123"));
        assertFalse(created.getChannelConfig().contains(FAKE_CIPHER));
    }

    @Test
    @DisplayName("T5-FAIL-CLOSED：加密失败抛业务异常，且 insert 绝不执行（绝不明文落库）")
    void create_encryptionFails_throwsAndNeverInserts() {
        when(cryptoService.encrypt(eq("AES"), anyString(), anyString(),
                isNull(), eq("ECB"), eq("PKCS5Padding")))
                .thenThrow(new RuntimeException("keystore down"));
        NotifyChannel c = newChannel("EMAIL",
                "{\"smtpHost\":\"smtp.example.com\",\"password\":\"secret-pwd-123\"}");

        assertThrows(GatewayException.class, () -> service.create(c));
        verify(notifyChannelMapper, never()).insert(any(NotifyChannel.class));
    }

    @Test
    @DisplayName("T5：无 password 字段（如 WECOM webhook）→ 不触发加密，原样落库")
    void create_noPassword_skipsEncryption() {
        NotifyChannel c = newChannel("WECOM", "{\"webhook\":\"https://example.com/hook\"}");
        service.create(c);

        assertEquals("{\"webhook\":\"https://example.com/hook\"}", persistedConfig.get());
        verify(cryptoService, never()).encrypt(anyString(), anyString(), anyString(),
                any(), anyString(), anyString());
    }

    @Test
    @DisplayName("R2：密文配置超过 1024 字符 → badRequest 明确报错，不静默截断")
    void create_overlongConfig_badRequest() {
        StringBuilder longFrom = new StringBuilder();
        for (int i = 0; i < 1010; i++) {
            longFrom.append('x');
        }
        NotifyChannel c = newChannel("EMAIL",
                "{\"smtpHost\":\"smtp.example.com\",\"from\":\"" + longFrom + "\",\"password\":\"p\"}");
        assertThrows(GatewayException.class, () -> service.create(c));
        verify(notifyChannelMapper, never()).insert(any(NotifyChannel.class));
    }

    // ============================== T6 掩码回写防线 ==============================

    @Test
    @DisplayName("T6：update 回传掩码 password → 保留库中 enc: 原值（防掩码覆盖真密文）")
    void update_maskedPassword_preservesExistingCipher() {
        NotifyChannel existing = newChannel("EMAIL",
                "{\"smtpHost\":\"smtp.example.com\",\"password\":\"enc:" + FAKE_CIPHER + "\"}");
        when(notifyChannelMapper.selectById(7L)).thenReturn(existing);

        // 前端把 get 接口回显的掩码原样回传
        NotifyChannel incoming = new NotifyChannel();
        incoming.setChannelConfig("{\"smtpHost\":\"smtp2.example.com\",\"password\":\"secr****-123\"}");
        service.update(7L, incoming);

        String persisted = persistedConfig.get();
        assertEquals("enc:" + FAKE_CIPHER, passwordOf(persisted),
                "掩码回写防线应保留库中密文，实际: " + persisted);
        assertEquals("smtp2.example.com",
                JSONUtil.parseObj(persisted).getStr("smtpHost"));
    }

    @Test
    @DisplayName("T6 变体：掩码为短密码回退格式 **** → 同样保留库中原值")
    void update_shortMask_preservesExistingCipher() {
        NotifyChannel existing = newChannel("EMAIL",
                "{\"smtpHost\":\"h\",\"password\":\"enc:" + FAKE_CIPHER + "\"}");
        when(notifyChannelMapper.selectById(7L)).thenReturn(existing);

        NotifyChannel incoming = new NotifyChannel();
        incoming.setChannelConfig("{\"smtpHost\":\"h\",\"password\":\"****\"}");
        service.update(7L, incoming);

        assertEquals("enc:" + FAKE_CIPHER, passwordOf(persistedConfig.get()));
    }

    @Test
    @DisplayName("T6 对照：update 传新明文 password → 正常走加密链路")
    void update_newPlaintextPassword_encrypts() {
        NotifyChannel existing = newChannel("EMAIL",
                "{\"smtpHost\":\"h\",\"password\":\"enc:" + FAKE_CIPHER + "\"}");
        when(notifyChannelMapper.selectById(7L)).thenReturn(existing);

        NotifyChannel incoming = new NotifyChannel();
        incoming.setChannelConfig("{\"smtpHost\":\"h\",\"password\":\"brand-new-pwd\"}");
        service.update(7L, incoming);

        assertEquals("enc:" + FAKE_CIPHER, passwordOf(persistedConfig.get()));
        verify(cryptoService, times(1)).encrypt(eq("AES"), eq("brand-new-pwd"),
                anyString(), isNull(), eq("ECB"), eq("PKCS5Padding"));
    }

    @Test
    @DisplayName("T6 兼容：历史明文配置 + 掩码回传 → 取回明文并顺势加密（演进式兼容，不清空不丢密码）")
    void update_maskedOverLegacyPlaintext_reencryptsLegacy() {
        NotifyChannel existing = newChannel("EMAIL",
                "{\"smtpHost\":\"h\",\"password\":\"legacy-plain-pwd\"}");
        when(notifyChannelMapper.selectById(7L)).thenReturn(existing);

        NotifyChannel incoming = new NotifyChannel();
        incoming.setChannelConfig("{\"smtpHost\":\"h\",\"password\":\"lega****-pwd\"}");
        service.update(7L, incoming);

        // 取回历史明文后走正常加密链路（掩码没有覆盖成真值，也没有清空）
        verify(cryptoService, times(1)).encrypt(eq("AES"), eq("legacy-plain-pwd"),
                anyString(), isNull(), eq("ECB"), eq("PKCS5Padding"));
        assertEquals("enc:" + FAKE_CIPHER, passwordOf(persistedConfig.get()));
    }

    // ============================== T7 传输脱敏 ==============================

    @Test
    @DisplayName("T7：get 返回掩码 —— 不含明文、不含密文")
    void get_masksPassword() {
        NotifyChannel stored = newChannel("EMAIL",
                "{\"smtpHost\":\"h\",\"password\":\"enc:" + FAKE_CIPHER + "\"}");
        when(notifyChannelMapper.selectById(9L)).thenReturn(stored);

        NotifyChannel resp = service.get(9L);
        // 掩码 = 首末各 4：密文 "enc:Q0lQ...Zg==" → "enc:****Zg=="
        assertEquals("enc:****Zg==", passwordOf(resp.getChannelConfig()));
        assertFalse(resp.getChannelConfig().contains(FAKE_CIPHER));
    }

    @Test
    @DisplayName("T7：list 逐条脱敏；无 password 字段的渠道原样返回")
    void list_masksEachPassword() {
        NotifyChannel withPwd = newChannel("EMAIL",
                "{\"smtpHost\":\"h\",\"password\":\"enc:" + FAKE_CIPHER + "\"}");
        NotifyChannel withoutPwd = newChannel("WECOM", "{\"webhook\":\"https://w/hook\"}");
        when(notifyChannelMapper.selectList(any())).thenReturn(Arrays.asList(withPwd, withoutPwd));

        java.util.List<NotifyChannel> result = service.list((Integer) null);
        assertEquals(2, result.size());
        assertTrue(result.get(0).getChannelConfig().contains("****"));
        assertFalse(result.get(0).getChannelConfig().contains(FAKE_CIPHER));
        assertEquals("{\"webhook\":\"https://w/hook\"}", result.get(1).getChannelConfig());
    }

    @Test
    @DisplayName("T7+：历史明文行脱敏不派生自明文（不泄漏首末各 4 位）—— §3.3 与 §7.6 冲突时的裁定")
    void list_legacyPlaintext_maskDerivesNothingFromPlaintext() {
        NotifyChannel legacy = newChannel("EMAIL",
                "{\"smtpHost\":\"h\",\"password\":\"P@ssw0rd-Test-1234\"}");
        when(notifyChannelMapper.selectList(any()))
                .thenReturn(java.util.Collections.singletonList(legacy));

        java.util.List<NotifyChannel> result = service.list((Integer) null);
        String cfg = result.get(0).getChannelConfig();
        // 固定掩码：不含明文，也不含明文的任何片段
        assertEquals("****", passwordOf(cfg));
        assertFalse(cfg.contains("P@ss"));
        assertFalse(cfg.contains("1234"));
        assertFalse(cfg.contains("P@ssw0rd-Test-1234"));
    }

    // ============================== 读路径（发送用解密） ==============================

    @Test
    @DisplayName("读路径：listByIds 解密 enc: 密文供发送；历史明文直接用")
    void listByIds_decryptsForSend() {
        NotifyChannel encCh = newChannel("EMAIL",
                "{\"smtpHost\":\"h\",\"password\":\"enc:" + FAKE_CIPHER + "\"}");
        NotifyChannel legacyCh = newChannel("EMAIL",
                "{\"smtpHost\":\"h\",\"password\":\"legacy-plain\"}");
        when(notifyChannelMapper.selectBatchIds(anyCollection()))
                .thenReturn(Arrays.asList(encCh, legacyCh));

        java.util.List<NotifyChannel> channels = service.listByIds("1,2");
        assertEquals("secret-pwd-123", passwordOf(channels.get(0).getChannelConfig()));
        assertEquals("legacy-plain", passwordOf(channels.get(1).getChannelConfig()));
        verify(cryptoService, times(1)).decrypt(eq("AES"), eq(FAKE_CIPHER),
                anyString(), isNull(), eq("ECB"), eq("PKCS5Padding"));
    }

    @Test
    @DisplayName("test()：发送用解密副本，lastTest* 回写仍为密文（不把明文写回库）")
    void test_sendsDecryptedCopy_persistsCipher() {
        NotifyChannel stored = newChannel("EMAIL",
                "{\"smtpHost\":\"h\",\"password\":\"enc:" + FAKE_CIPHER + "\"}");
        when(notifyChannelMapper.selectById(11L)).thenReturn(stored);
        ArgumentCaptor<NotifyChannel> sendCaptor = ArgumentCaptor.forClass(NotifyChannel.class);
        when(notifySender.send(sendCaptor.capture(), anyString(), anyString())).thenReturn(true);

        service.test(11L);

        // 发送侧拿到的是解密后的明文配置
        assertEquals("secret-pwd-123", passwordOf(sendCaptor.getValue().getChannelConfig()));
        // 回写 updateById 的仍是 enc: 密文 + lastTest 结果
        String persisted = persistedConfig.get();
        assertEquals("enc:" + FAKE_CIPHER, passwordOf(persisted));
        assertEquals("SUCCESS", stored.getLastTestResult());
    }

    @Test
    @DisplayName("test()：发送失败 → lastTestResult=FAILED，同样不落明文")
    void test_sendFails_recordsFailed() {
        NotifyChannel stored = newChannel("WECOM", "{\"webhook\":\"https://w/hook\"}");
        when(notifyChannelMapper.selectById(11L)).thenReturn(stored);
        when(notifySender.send(any(NotifyChannel.class), anyString(), anyString()))
                .thenReturn(false);

        service.test(11L);

        assertEquals("FAILED", stored.getLastTestResult());
        assertTrue(persistedConfig.get().contains("webhook"));
    }

    // ============================== 边界 ==============================

    @Test
    @DisplayName("边界：非 JSON 配置原样存储，不触发加密（发送侧自会失败）")
    void create_nonJsonConfig_storedAsIs() {
        NotifyChannel c = newChannel("WECOM", "not-a-json");
        service.create(c);
        assertEquals("not-a-json", persistedConfig.get());
        verify(cryptoService, never()).encrypt(anyString(), anyString(), anyString(),
                any(), anyString(), anyString());
    }

    @Test
    @DisplayName("边界：解密失败（如密钥轮换）→ 原样使用，不抛异常（发送以认证失败告终）")
    void listByIds_decryptFailure_usesRaw() {
        when(cryptoService.decrypt(anyString(), anyString(), anyString(),
                any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("key rotated"));
        NotifyChannel stored = newChannel("EMAIL",
                "{\"smtpHost\":\"h\",\"password\":\"enc:" + FAKE_CIPHER + "\"}");
        when(notifyChannelMapper.selectBatchIds(anyCollection()))
                .thenReturn(java.util.Collections.singletonList(stored));

        java.util.List<NotifyChannel> channels = service.listByIds("1");
        // 原样返回密文（不抛、不静默清空）
        assertEquals("enc:" + FAKE_CIPHER, passwordOf(channels.get(0).getChannelConfig()));
    }
}
