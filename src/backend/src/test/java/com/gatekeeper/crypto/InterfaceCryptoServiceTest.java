package com.gatekeeper.crypto;

import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.service.SysInterfaceCryptoConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link InterfaceCryptoServiceImpl} 单测 — T17。
 *
 * <p><b>为什么用真的 {@link CryptoServiceImpl}</b>：本类要验证的正是「密文能不能被
 * 正确地还原成明文」这类<b>端到端密码学行为</b>。若把 CryptoService 也 mock 掉，
 * 测试就退化成"验证我调用了 encrypt()"，证明不了任何事。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>加解密往返 + 密文格式 {@code enc:v1:<iv>:<cipher>}；</li>
 *   <li><b>随机 IV</b>：同一明文两次加密得到不同密文（抗相等性分析）；</li>
 *   <li>幂等：对已是密文的值不再二次加密；</li>
 *   <li>演进式兼容：历史明文 / 空值 / 格式非法密文 → 原样返回且<b>不抛异常</b>
 *       （否则整个列表接口 500）；</li>
 *   <li><b>盲索引</b>：确定性、64 位小写 hex、带 KEK、带域分离前缀
 *       （≠ 裸 SHA256、≠ 无前缀 HMAC）、空值 → null；</li>
 *   <li><b>FAIL-CLOSED</b>：无 KEK 时 encrypt / blindIndex 必须抛异常，
 *       绝不明文落库、绝不让网关静默全 404；</li>
 *   <li>{@code applyToInterface} 的 hash 与开关关系（hash <b>无论开关都算</b>）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InterfaceCryptoService 字段级加解密与盲索引 T17")
class InterfaceCryptoServiceTest {

    /** 与 NotifyChannelCryptoTest 同款测试 KEK（≥32 字节，AES-256） */
    private static final String AES_KEY = "TEST_32_BYTES_LONG_KEY_FOR_AES_256";
    private static final String OTHER_KEY = "ANOTHER_32_BYTES_LONG_KEY_FOR_KEK!";

    @Mock
    private SysInterfaceCryptoConfigService configService;

    private InterfaceCryptoServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InterfaceCryptoServiceImpl(new CryptoServiceImpl(), configService);
        ReflectionTestUtils.setField(service, "aesDbKey", AES_KEY);
        when(configService.isEnabled()).thenReturn(true);
    }

    // =====================================================================
    // 单字段加解密
    // =====================================================================

    @Test
    @DisplayName("encryptField 产出 enc:v1: 前缀密文，密文中不含明文，且可还原")
    void encryptField_formatAndRoundTrip() {
        String plain = "/order/create";
        String cipher = service.encryptField(plain);

        assertTrue(service.isEncrypted(cipher), "必须带 enc:v1: 前缀");
        assertTrue(cipher.startsWith(InterfaceCryptoService.ENC_PREFIX));
        assertNotEquals(plain, cipher);
        assertFalse(cipher.contains(plain), "密文里不能出现明文子串");

        assertEquals(plain, service.decryptField(cipher), "往返必须还原");
    }

    @Test
    @DisplayName("随机 IV：同一明文两次加密得到不同密文（抗相等性分析）")
    void encryptField_randomIv_makesCiphertextNonDeterministic() {
        String a = service.encryptField("/same/path");
        String b = service.encryptField("/same/path");

        assertNotEquals(a, b, "同一明文两次加密必须不同（随机 IV）");
        // 两者都能还原成同一明文
        assertEquals("/same/path", service.decryptField(a));
        assertEquals("/same/path", service.decryptField(b));
    }

    @Test
    @DisplayName("encryptField 幂等：已是密文则原样返回，不二次加密")
    void encryptField_isIdempotentOnCiphertext() {
        String cipher = service.encryptField("/a/b");
        assertEquals(cipher, service.encryptField(cipher));
        assertEquals(cipher, service.encryptField(service.encryptField(cipher)));
    }

    @Test
    @DisplayName("encryptField 对 null / 空串 / 纯空白原样返回")
    void encryptField_blankPassthrough() {
        assertNull(service.encryptField(null));
        assertEquals("", service.encryptField(""));
        assertEquals("   ", service.encryptField("   "));
    }

    @Test
    @DisplayName("decryptField 对历史明文原样返回（演进式兼容，无前缀即明文）")
    void decryptField_historicalPlaintextPassthrough() {
        assertEquals("/legacy/path", service.decryptField("/legacy/path"));
        assertEquals("plain-field", service.decryptField("plain-field"));
        assertNull(service.decryptField(null));
        assertEquals("", service.decryptField(""));
    }

    @Test
    @DisplayName("decryptField 对分段数非法的密文原样返回，不抛异常")
    void decryptField_malformedSegments_noThrow() {
        String bad = "enc:v1:onlythree";
        assertEquals(bad, service.decryptField(bad));
    }

    @Test
    @DisplayName("decryptField 对 IV 长度非 16 字节的密文原样返回，不抛异常")
    void decryptField_badIvLength_noThrow() {
        String badIv = Base64.getEncoder().encodeToString(new byte[8]); // 8 字节，非法
        String bad = InterfaceCryptoService.ENC_PREFIX + badIv + ":QUJDREVGR0g=";
        assertEquals(bad, service.decryptField(bad));
    }

    @Test
    @DisplayName("decryptField 对密文正文非 Base64 的值原样返回，不抛异常（避免列表 500）")
    void decryptField_brokenCipherBody_noThrow() {
        String okIv = Base64.getEncoder().encodeToString(new byte[16]);
        String bad = InterfaceCryptoService.ENC_PREFIX + okIv + ":!!!not-base64!!!";
        assertEquals(bad, service.decryptField(bad));
    }

    @Test
    @DisplayName("🔴 FAIL-CLOSED：无 KEK 时 encryptField 抛异常，绝不明文落库")
    void encryptField_failClosed_whenNoKey() {
        ReflectionTestUtils.setField(service, "aesDbKey", "");
        assertThrows(IllegalStateException.class, () -> service.encryptField("/order/create"));
    }

    @Test
    @DisplayName("🔴 FAIL-CLOSED：无 KEK 时 blindIndex 抛异常（否则网关静默全 404）")
    void blindIndex_failClosed_whenNoKey() {
        ReflectionTestUtils.setField(service, "aesDbKey", null);
        assertThrows(IllegalStateException.class, () -> service.blindIndex("/order/create"));
    }

    // =====================================================================
    // 盲索引
    // =====================================================================

    @Test
    @DisplayName("blindIndex 确定性：同输入恒同值，且为 64 位小写 hex")
    void blindIndex_deterministicHex64() {
        String h1 = service.blindIndex("/order/create");
        String h2 = service.blindIndex("/order/create");

        assertEquals(h1, h2, "必须确定 —— 否则网关等值查询匹配不上");
        assertEquals(64, h1.length(), "HMAC-SHA256 → 64 位 hex");
        assertTrue(h1.matches("[0-9a-f]{64}"), "必须是小写十六进制");

        assertNotEquals(h1, service.blindIndex("/order/create2"), "不同路径必须不同 hash");
    }

    @Test
    @DisplayName("blindIndex 空值返回 null")
    void blindIndex_blankReturnsNull() {
        assertNull(service.blindIndex(null));
        assertNull(service.blindIndex(""));
        assertNull(service.blindIndex("   "));
    }

    @Test
    @DisplayName("blindIndex 跨实例一致（网关是另一进程，必须算出同一 hash）")
    void blindIndex_stableAcrossInstances() {
        InterfaceCryptoServiceImpl other =
                new InterfaceCryptoServiceImpl(new CryptoServiceImpl(), configService);
        ReflectionTestUtils.setField(other, "aesDbKey", AES_KEY);
        assertEquals(service.blindIndex("/a/b"), other.blindIndex("/a/b"));
    }

    @Test
    @DisplayName("🔴 blindIndex 带 KEK：换 KEK 后 hash 变化（≠ 裸 SHA256，抗彩虹表）")
    void blindIndex_isKeyed_notBareDigest() throws Exception {
        String h = service.blindIndex("/order/create");

        // 1) 不等于裸 SHA256 —— 若是裸摘要，路径低熵可被秒查彩虹表
        assertNotEquals(cn.hutool.crypto.SecureUtil.sha256("/order/create"), h);

        // 2) 换一把 KEK，hash 必须变
        ReflectionTestUtils.setField(service, "aesDbKey", OTHER_KEY);
        assertNotEquals(h, service.blindIndex("/order/create"));

        // 3) 域分离：不等于「无前缀 HMAC」
        ReflectionTestUtils.setField(service, "aesDbKey", AES_KEY);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] noDomain = mac.doFinal("/order/create".getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : noDomain) {
            hex.append(String.format("%02x", b));
        }
        assertNotEquals(hex.toString(), h, "必须加域分离前缀，避免与加密用途重合");
    }

    // =====================================================================
    // 实体级：接口
    // =====================================================================

    @Test
    @DisplayName("applyToInterface（开关启用）：路径加密 + 盲索引就位")
    void applyToInterface_enabled() {
        ApiInterface iface = new ApiInterface();
        iface.setInterfacePath("/order/create");

        service.applyToInterface(iface);

        assertTrue(service.isEncrypted(iface.getInterfacePath()));
        assertEquals(service.blindIndex("/order/create"), iface.getInterfacePathHash());
    }

    @Test
    @DisplayName("🔴 applyToInterface（开关关闭）：路径存明文，但盲索引照算（避免开关切换后 hash 缺失）")
    void applyToInterface_disabled_stillComputesHash() {
        when(configService.isEnabled()).thenReturn(false);
        ApiInterface iface = new ApiInterface();
        iface.setInterfacePath("/order/create");

        service.applyToInterface(iface);

        assertEquals("/order/create", iface.getInterfacePath());
        assertEquals(service.blindIndex("/order/create"), iface.getInterfacePathHash());
    }

    @Test
    @DisplayName("applyToInterface 路径为空时不动任何列（如 updateStatus 只带 id+status）")
    void applyToInterface_blankPath_noop() {
        ApiInterface iface = new ApiInterface();
        service.applyToInterface(iface);
        assertNull(iface.getInterfacePath());
        assertNull(iface.getInterfacePathHash());

        ApiInterface nullIface = null;
        service.applyToInterface(nullIface); // 不应 NPE
    }

    @Test
    @DisplayName("applyToInterface 对已是密文的路径不二次加密、不动已有 hash")
    void applyToInterface_alreadyCiphertext_noDoubleEncrypt() {
        String cipher = service.encryptField("/a/b");
        ApiInterface iface = new ApiInterface();
        iface.setInterfacePath(cipher);
        iface.setInterfacePathHash("EXISTING_HASH_FROM_DB");

        service.applyToInterface(iface);

        assertEquals(cipher, iface.getInterfacePath());
        assertEquals("EXISTING_HASH_FROM_DB", iface.getInterfacePathHash());
    }

    @Test
    @DisplayName("decryptInPlace（接口）：密文还原明文，历史明文原样保留")
    void decryptInPlace_interface() {
        ApiInterface iface = new ApiInterface();
        iface.setInterfacePath(service.encryptField("/a/b"));
        service.decryptInPlace(iface);
        assertEquals("/a/b", iface.getInterfacePath());

        ApiInterface legacy = new ApiInterface();
        legacy.setInterfacePath("/legacy/path");
        service.decryptInPlace(legacy);
        assertEquals("/legacy/path", legacy.getInterfacePath());

        service.decryptInPlace((ApiInterface) null); // 不应 NPE
    }

    // =====================================================================
    // 实体级：参数
    // =====================================================================

    @Test
    @DisplayName("applyToParam（开关启用）：契约内容三列加密，结构列保持明文")
    void applyToParam_enabled_encryptsContentColumnsOnly() {
        ApiParam p = new ApiParam();
        p.setFieldName("skuId");
        p.setExample("1001");
        p.setDescription("商品ID");
        p.setFieldType("string");
        p.setErrorCode("E001");
        p.setParamType(3);

        service.applyToParam(p);

        assertTrue(service.isEncrypted(p.getFieldName()));
        assertTrue(service.isEncrypted(p.getExample()));
        assertTrue(service.isEncrypted(p.getDescription()));
        // 能还原
        assertEquals("skuId", service.decryptField(p.getFieldName()));
        assertEquals("1001", service.decryptField(p.getExample()));
        assertEquals("商品ID", service.decryptField(p.getDescription()));
        // 结构列刻意不加密
        assertEquals("string", p.getFieldType());
        assertEquals("E001", p.getErrorCode());
        assertEquals(Integer.valueOf(3), p.getParamType());
    }

    @Test
    @DisplayName("applyToParam（开关关闭）：三列原样明文")
    void applyToParam_disabled_plaintext() {
        when(configService.isEnabled()).thenReturn(false);
        ApiParam p = new ApiParam();
        p.setFieldName("skuId");
        p.setExample("1001");
        p.setDescription("商品ID");

        service.applyToParam(p);

        assertEquals("skuId", p.getFieldName());
        assertEquals("1001", p.getExample());
        assertEquals("商品ID", p.getDescription());
    }

    @Test
    @DisplayName("decryptInPlace（参数）：三列还原，null 安全")
    void decryptInPlace_param() {
        ApiParam p = new ApiParam();
        p.setFieldName(service.encryptField("skuId"));
        p.setExample(service.encryptField("1001"));
        p.setDescription(service.encryptField("商品ID"));

        service.decryptInPlace(p);

        assertEquals("skuId", p.getFieldName());
        assertEquals("1001", p.getExample());
        assertEquals("商品ID", p.getDescription());

        service.decryptInPlace((ApiParam) null);
        service.decryptInPlace(new ApiParam());
    }

    // =====================================================================
    // 判定与委托
    // =====================================================================

    @Test
    @DisplayName("isEncrypted / isMask 语义")
    void isEncrypted_and_isMask_semantics() {
        assertFalse(service.isEncrypted(null));
        assertFalse(service.isEncrypted("/plain"));
        assertTrue(service.isEncrypted("enc:v1:aaa:bbb"));
        assertFalse(service.isEncrypted("ENC:V1:aaa:bbb"), "前缀大小写敏感");

        assertFalse(service.isMask(null));
        assertFalse(service.isMask(""));
        assertFalse(service.isMask("***"), "长度不符不算掩码");
        assertTrue(service.isMask("****"));
        assertTrue(service.isMask("  ****  "), "前后空白应被 trim");
        assertFalse(service.isMask("/order/create"));
    }

    @Test
    @DisplayName("maskPath 返回固定 ****（绝不派生自明文）")
    void maskPath_isFixedMask() {
        assertEquals("****", service.maskPath());
        assertEquals(InterfaceCryptoService.MASK, service.maskPath());
    }

    @Test
    @DisplayName("isEnabled / invalidate 委托给开关服务")
    void delegatesToConfigService() {
        when(configService.isEnabled()).thenReturn(false);
        assertFalse(service.isEnabled());
        when(configService.isEnabled()).thenReturn(true);
        assertTrue(service.isEnabled());

        service.invalidate();
        org.mockito.Mockito.verify(configService).invalidate();
    }
}
