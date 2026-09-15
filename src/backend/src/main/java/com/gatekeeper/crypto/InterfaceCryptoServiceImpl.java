package com.gatekeeper.crypto;

import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.service.SysInterfaceCryptoConfigService;
import com.gatekeeper.util.CryptoKeyUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 接口信息字段级加解密实现 — T17
 *
 * <p>实现要点、盲索引原理与密文格式见 {@link InterfaceCryptoService} 的类注释。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
@Slf4j
@Service
public class InterfaceCryptoServiceImpl implements InterfaceCryptoService {

    /** 密文分隔符：enc:v1:<iv>:<cipher> */
    private static final String SEP = ":";
    /** 固定分段数：enc / v1 / iv / cipher = 4 段 */
    private static final int SEGMENTS = 4;
    /** AES-CBC 标准块长（字节），也是 IV 长度 */
    private static final int IV_BYTES = 16;

    private final CryptoService cryptoService;
    private final SysInterfaceCryptoConfigService configService;

    /** 落库 KEK：与 app_secret / 渠道密码同一把（KEK 单一来源） */
    @Value("${gatekeeper.crypto.aes-key}")
    private String aesDbKey;

    public InterfaceCryptoServiceImpl(CryptoService cryptoService,
                                      SysInterfaceCryptoConfigService configService) {
        this.cryptoService = cryptoService;
        this.configService = configService;
    }

    @Override
    public boolean isEnabled() {
        return configService.isEnabled();
    }

    @Override
    public void invalidate() {
        configService.invalidate();
    }

    // =====================================================================
    // 单字段加解密
    // =====================================================================

    @Override
    public String encryptField(String plain) {
        if (!StringUtils.hasText(plain)) {
            return plain;
        }
        if (isEncrypted(plain)) {
            return plain; // 幂等：已是密文不重复加密
        }
        try {
            String key = CryptoKeyUtil.toBase64Key(aesDbKey);
            // 每次都用新的随机 IV —— 同一明文两次加密得到不同密文（抗相等性分析）
            String ivB64 = CryptoServiceImpl.generateIV();
            String cipherB64 = cryptoService.encrypt(
                    "AES", plain, key, ivB64, "CBC", "PKCS5Padding");
            return ENC_PREFIX + ivB64 + SEP + cipherB64;
        } catch (Exception e) {
            // FAIL-CLOSED：加密失败必须拒绝落库（绝不明文落库），由调用方转成业务异常
            log.error("接口信息字段加密失败: {}", e.getMessage());
            throw new IllegalStateException("接口信息加密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String decryptField(String stored) {
        if (!StringUtils.hasText(stored) || !isEncrypted(stored)) {
            return stored; // 历史明文 / 空值：原样返回（演进式兼容）
        }
        String[] parts = stored.split(SEP, SEGMENTS + 1);
        if (parts.length != SEGMENTS || parts[2].isEmpty() || parts[3].isEmpty()) {
            log.warn("接口信息密文格式非法，按原值返回: head={}", safeHead(stored));
            return stored;
        }
        try {
            if (Base64.getDecoder().decode(parts[2]).length != IV_BYTES) {
                log.warn("接口信息密文 IV 长度非 {} 字节，按原值返回: head={}", IV_BYTES, safeHead(stored));
                return stored;
            }
            String key = CryptoKeyUtil.toBase64Key(aesDbKey);
            return cryptoService.decrypt(
                    "AES", parts[3], key, parts[2], "CBC", "PKCS5Padding");
        } catch (Exception e) {
            // 解密失败（KEK 轮换 / IV 非 Base64 / 密文损坏）→ 原样返回密文 + WARN。
            // 刻意不抛异常：密文本身不泄漏信息，而抛异常会让整个列表/详情接口 500。
            log.error("接口信息字段解密失败（KEK 是否已轮换？见 docs/CONTRACTS §18 R8）: {}", e.getMessage());
            return stored;
        }
    }

    @Override
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    @Override
    public boolean isMask(String value) {
        return value != null && MASK.equals(value.trim());
    }

    @Override
    public String blindIndex(String plain) {
        if (!StringUtils.hasText(plain)) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // 域分离：HMAC 密钥 = KEK 原始字节；消息加固定前缀，使同一把 KEK 下
            // 「盲索引」与「加密」两个用途互不干扰（密钥复用但不跨用途重合）
            mac.init(new SecretKeySpec(aesDbKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal((BLIND_INDEX_DOMAIN + plain).getBytes(StandardCharsets.UTF_8));
            return toHex(out);
        } catch (Exception e) {
            // 盲索引算不出来 = 网关路由会断，属于**不可降级**的故障，必须抛出
            log.error("接口路径盲索引计算失败: {}", e.getMessage());
            throw new IllegalStateException("接口路径盲索引计算失败: " + e.getMessage(), e);
        }
    }

    // =====================================================================
    // 实体级（接口 / 参数）
    // =====================================================================

    @Override
    public void applyToInterface(ApiInterface iface) {
        if (iface == null) {
            return;
        }
        String path = iface.getInterfacePath();
        if (!StringUtils.hasText(path)) {
            // 未提供路径（如 updateStatus 只带 id+status）：不碰这两列，
            // MyBatis-Plus updateById 的 NOT_NULL 策略会跳过 null
            return;
        }
        if (isEncrypted(path)) {
            return; // 更新场景自库里带出的密文：不二次加密，hash 已随行存在
        }
        iface.setInterfacePathHash(blindIndex(path));
        iface.setInterfacePath(isEnabled() ? encryptField(path) : path);
    }

    @Override
    public void decryptInPlace(ApiInterface iface) {
        if (iface == null || !StringUtils.hasText(iface.getInterfacePath())) {
            return;
        }
        iface.setInterfacePath(decryptField(iface.getInterfacePath()));
    }

    @Override
    public void applyToParam(ApiParam param) {
        if (param == null) {
            return;
        }
        if (isEnabled()) {
            param.setFieldName(encryptField(param.getFieldName()));
            param.setExample(encryptField(param.getExample()));
            param.setDescription(encryptField(param.getDescription()));
        }
    }

    @Override
    public void decryptInPlace(ApiParam param) {
        if (param == null) {
            return;
        }
        param.setFieldName(decryptField(param.getFieldName()));
        param.setExample(decryptField(param.getExample()));
        param.setDescription(decryptField(param.getDescription()));
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 字节数组 → 小写十六进制 */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 仅用于日志：绝不把完整密文/明文写进日志 */
    private static String safeHead(String s) {
        return s.length() <= 12 ? s.substring(0, Math.min(4, s.length())) + "…" : s.substring(0, 12) + "…";
    }
}
