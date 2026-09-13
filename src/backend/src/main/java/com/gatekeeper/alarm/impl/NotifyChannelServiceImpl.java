package com.gatekeeper.alarm.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.alarm.NotifyChannelService;
import com.gatekeeper.alarm.NotifySender;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.entity.NotifyChannel;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.NotifyChannelMapper;
import com.gatekeeper.util.CryptoKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 通知渠道服务实现 — T04-C 告警域；T09-N1 增加配置加密链路
 *
 * <p>发送动作委托 {@link NotifySender}（渠道类型决定实发/桩发）。test 接口会回写测试结果。</p>
 *
 * <h3>T09-N1 配置安全（方案 §3.3，照抄 {@code AppCredentialServiceImpl.encryptSecret} 范式）</h3>
 * <ul>
 *   <li><strong>写路径 FAIL-CLOSED</strong>：channelConfig 中仅 {@code password} 字段加密，
 *       密文带 {@code enc:} 前缀落库；加密失败抛业务异常拒绝落库（绝不明文落库）。</li>
 *   <li><strong>读路径</strong>：发送前解密（{@code enc:} 前缀 → 解密；历史明文 → 直接用并 log warn）。</li>
 *   <li><strong>传输脱敏</strong>：list / detail / create 返回前把 password 替换为掩码
 *       （首4+****+末4），响应不含明文也不含密文。</li>
 *   <li><strong>掩码回写防线</strong>：update 若传入掩码格式 password（前端编辑回显原样回传），
 *       保留库中原值，防掩码覆盖真密文。</li>
 *   <li>密钥与 app_secret <strong>共用</strong> {@code gatekeeper.crypto.aes-key}（KEK 单一来源；
 *       该密钥一旦轮换，channel_config 与 app_secret 须同批重加密 —— 方案 R8 约束留档）。</li>
 * </ul>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)，T09-N1 增强
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyChannelServiceImpl extends ServiceImpl<NotifyChannelMapper, NotifyChannel>
        implements NotifyChannelService {

    /** channelConfig 中 password 密文的标记前缀 */
    private static final String ENC_PREFIX = "enc:";

    /** 掩码格式：首4 + **** + 末4（与 toSecretMask 输出一致） */
    private static final Pattern MASK_PATTERN = Pattern.compile("^.{4}\\*{4}.{4}$");

    /** 不派生自存储值的固定掩码（用于历史明文行，绝不泄漏明文片段） */
    private static final String MASK_ONLY = "****";

    /** channelConfig 列宽（varchar(1024)），编码层校验超长即明确报错（方案 R2） */
    private static final int CONFIG_MAX_LENGTH = 1024;

    private final NotifySender notifySender;

    /** 渠道密码落库 AES-256 ECB 密钥（与 app_secret 共用，KEK 单一来源） */
    @Value("${gatekeeper.crypto.aes-key}")
    private String aesDbKey;

    private final CryptoService cryptoService;

    @Override
    public List<NotifyChannel> list(Integer status) {
        QueryWrapper<NotifyChannel> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("created_at");
        List<NotifyChannel> channels = baseMapper.selectList(wrapper);
        // 传输脱敏（方案 §3.3）：响应不含明文也不含密文
        if (channels != null) {
            channels.forEach(this::maskForResponse);
        }
        return channels;
    }

    @Override
    public NotifyChannel get(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("渠道ID不能为空");
        }
        NotifyChannel channel = baseMapper.selectById(id);
        if (channel == null) {
            throw GatewayException.notFound("通知渠道不存在: id=" + id);
        }
        maskForResponse(channel);
        return channel;
    }

    @Override
    public NotifyChannel create(NotifyChannel channel) {
        if (channel == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (!StringUtils.hasText(channel.getChannelName())) {
            throw GatewayException.badRequest("渠道名称不能为空");
        }
        if (!StringUtils.hasText(channel.getChannelType())) {
            throw GatewayException.badRequest("渠道类型不能为空");
        }
        NotifyChannel entity = new NotifyChannel();
        BeanUtils.copyProperties(channel, entity);
        entity.setId(null);
        entity.setStatus(channel.getStatus() == null ? 1 : channel.getStatus());
        // 写路径 FAIL-CLOSED：password 加密（enc: 前缀）；失败抛异常拒绝落库
        entity.setChannelConfig(encryptChannelConfig(entity.getChannelConfig()));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(entity);
        log.info("NotifyChannel created: id={}, name={}, type={}",
                entity.getId(), entity.getChannelName(), entity.getChannelType());
        // 响应脱敏：不把密文带回前端
        maskForResponse(entity);
        return entity;
    }

    @Override
    public void update(Long id, NotifyChannel channel) {
        if (id == null) {
            throw GatewayException.badRequest("渠道ID不能为空");
        }
        if (channel == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        NotifyChannel existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("通知渠道不存在: id=" + id);
        }
        if (StringUtils.hasText(channel.getChannelName())) {
            existing.setChannelName(channel.getChannelName());
        }
        if (StringUtils.hasText(channel.getChannelType())) {
            existing.setChannelType(channel.getChannelType());
        }
        if (StringUtils.hasText(channel.getChannelConfig())) {
            // 掩码回写防线 + 写路径加密（见 mergeAndEncryptConfig）
            existing.setChannelConfig(
                    mergeAndEncryptConfig(channel.getChannelConfig(), existing.getChannelConfig()));
        }
        if (channel.getStatus() != null) {
            existing.setStatus(channel.getStatus());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("NotifyChannel updated: id={}", id);
    }

    @Override
    public void delete(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("渠道ID不能为空");
        }
        NotifyChannel existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("通知渠道不存在: id=" + id);
        }
        // 引用完整性检查（T07-A）：alarm_rule.channel_ids 为逗号分隔字符串且无外键约束，
        // 被引用时拒绝删除 —— 避免留下悬空渠道 id 导致告警静默失效。
        // 选择「拒绝式」而非「级联清理」：级联会静默改写其它规则配置，在安全审计产品里不可追溯。
        long referencingRules = baseMapper.countRulesUsingChannel(id);
        if (referencingRules > 0) {
            throw GatewayException.badRequest(
                    "该通知渠道被 " + referencingRules + " 条告警规则引用，请先解除引用后再删除");
        }
        baseMapper.deleteById(id);
        log.info("NotifyChannel deleted: id={}, name={}", id, existing.getChannelName());
    }

    @Override
    public void test(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("渠道ID不能为空");
        }
        NotifyChannel channel = baseMapper.selectById(id);
        if (channel == null) {
            throw GatewayException.notFound("通知渠道不存在: id=" + id);
        }
        String name = channel.getChannelName() == null ? ("channel#" + id) : channel.getChannelName();
        String title = "[测试通知] " + name;
        String content = "这是一条测试通知，用于验证渠道「" + name + "」可达性。";
        boolean ok;
        try {
            // 发送用副本（password 解密），落库回写 lastTest* 仍用原密文实体
            NotifyChannel sendCopy = new NotifyChannel();
            BeanUtils.copyProperties(channel, sendCopy);
            sendCopy.setChannelConfig(decryptChannelConfig(channel.getChannelConfig()));
            ok = notifySender.send(sendCopy, title, content);
        } catch (Exception e) {
            log.error("test send failed channelId={}", id, e);
            ok = false;
        }
        channel.setLastTestTime(LocalDateTime.now());
        channel.setLastTestResult(ok ? "SUCCESS" : "FAILED");
        baseMapper.updateById(channel);
    }

    @Override
    public List<NotifyChannel> listByIds(String commaIds) {
        if (!StringUtils.hasText(commaIds)) {
            return Collections.emptyList();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String s : commaIds.split(",")) {
            s = s.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                log.warn("bad channel id ignored: {}", s);
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<NotifyChannel> channels = baseMapper.selectBatchIds(ids);
        // 读路径：发送前解密 password（仅用于发送，不落库不外发）
        if (channels != null) {
            channels.forEach(ch -> ch.setChannelConfig(
                    decryptChannelConfig(ch.getChannelConfig())));
        }
        return channels;
    }

    // =====================================================================
    // T09-N1 配置加密链路（方案 §3.3，照抄 AppCredentialServiceImpl 范式）
    // =====================================================================

    /**
     * 写路径加密：channelConfig 中仅 {@code password} 字段加密并加 {@code enc:} 前缀。
     *
     * <p>FAIL-CLOSED：加密失败抛 {@link GatewayException}，拒绝落库（绝不明文落库）。
     * 已带 {@code enc:} 前缀（保留场景）或无 password 字段 / 非 JSON 配置则原样返回。</p>
     */
    private String encryptChannelConfig(String config) {
        if (!StringUtils.hasText(config)) {
            return config;
        }
        JSONObject cfg;
        try {
            cfg = JSONUtil.parseObj(config);
        } catch (Exception e) {
            // 非 JSON 配置无 password 字段可加密，原样存储（发送侧会因解析失败而 false）
            log.warn("channelConfig is not JSON, store as-is");
            return config;
        }
        String pwd = cfg.getStr("password");
        if (!StringUtils.hasText(pwd) || pwd.startsWith(ENC_PREFIX)) {
            return cfg.toString();
        }
        try {
            String key = CryptoKeyUtil.toBase64Key(aesDbKey);
            String cipher = cryptoService.encrypt("AES", pwd, key, null, "ECB", "PKCS5Padding");
            cfg.set("password", ENC_PREFIX + cipher);
            String out = cfg.toString();
            if (out.length() > CONFIG_MAX_LENGTH) {
                // 方案 R2：超长给出明确报错而非静默截断
                throw GatewayException.badRequest(
                        "渠道配置超过 " + CONFIG_MAX_LENGTH + " 字符上限，请精简收件人或改用更短的密码");
            }
            return out;
        } catch (GatewayException ge) {
            throw ge;
        } catch (Exception e) {
            log.error("Encrypt channel password failed: {}", e.getMessage(), e);
            throw GatewayException.badGateway("渠道密码加密失败");
        }
    }

    /**
     * update 合并 + 掩码回写防线。
     *
     * <p>若传入的 password 是掩码格式（前端编辑回显后原样回传），则保留库中原值
     * （enc: 密文或历史明文），防止掩码覆盖真密文；否则按写路径正常加密。</p>
     */
    private String mergeAndEncryptConfig(String incoming, String existing) {
        JSONObject inc;
        try {
            inc = JSONUtil.parseObj(incoming);
        } catch (Exception e) {
            log.warn("incoming channelConfig is not JSON, store as-is");
            return incoming;
        }
        String pwd = inc.getStr("password");
        if (pwd != null && isMaskedPassword(pwd)) {
            String preserved = preserveExistingPassword(incoming, existing);
            inc.set("password", preserved);
            return encryptChannelConfig(inc.toString());
        }
        return encryptChannelConfig(incoming);
    }

    /**
     * 从库中既有配置取回 password 原值（找不到则原样返回 incoming —— 保持行为可预期）。
     */
    private String preserveExistingPassword(String incoming, String existing) {
        if (StringUtils.hasText(existing)) {
            try {
                JSONObject old = JSONUtil.parseObj(existing);
                String oldPwd = old.getStr("password");
                if (StringUtils.hasText(oldPwd)) {
                    return oldPwd;
                }
            } catch (Exception e) {
                log.warn("existing channelConfig is not JSON, cannot preserve password");
            }
        }
        return incoming;
    }

    /**
     * 读路径解密：{@code enc:} 前缀 → 解密；历史明文 → 直接用并 log warn（演进式兼容，不强制清洗）。
     * 解密失败 → 原样返回（发送将以认证失败告终，不抛异常）。
     */
    private String decryptChannelConfig(String config) {
        if (!StringUtils.hasText(config)) {
            return config;
        }
        try {
            JSONObject cfg = JSONUtil.parseObj(config);
            String pwd = cfg.getStr("password");
            if (pwd == null) {
                return config;
            }
            if (pwd.startsWith(ENC_PREFIX)) {
                try {
                    String key = CryptoKeyUtil.toBase64Key(aesDbKey);
                    String plain = cryptoService.decrypt(
                            "AES", pwd.substring(ENC_PREFIX.length()), key, null, "ECB", "PKCS5Padding");
                    cfg.set("password", plain);
                    return cfg.toString();
                } catch (Exception e) {
                    log.error("Decrypt channel password failed (key rotated? see 方案 R8), use raw value");
                    return config;
                }
            }
            log.warn("channel password stored in PLAINTEXT (legacy), using as-is — 建议编辑保存一次以加密");
            return config;
        } catch (Exception e) {
            return config;
        }
    }

    /**
     * 传输脱敏：password 字段替换为掩码。就地修改。
     *
     * <p>T09-N1 验收裁定（lead，2026-09-13，E2E 实测发现 §3.3 与 §7.6 冲突后拍板）：
     * §3.3 字面要求「首4+****+末4」，而 §7.6 要求「响应不含明文与密文」—— 二者在
     * <strong>历史明文行</strong>上不可兼得：对明文套 toSecretMask 会泄漏真密码的前 4 + 末 4 位。
     * 故按「绝不泄漏明文片段」优先：</p>
     * <ul>
     *   <li>{@code enc:} 密文行 → 保留 §3.3 格式 {@code 首4+****+末4}（泄漏的仅为密文片段，
     *       无 KEK 不可利用，且 {@code enc:} 前缀本身用于告知「已加密存储」）；</li>
     *   <li>历史明文行 → 返回固定 {@code "****"}，<strong>不派生自明文</strong>。</li>
     * </ul>
     * <p>两种掩码均被 {@link #isMaskedPassword} 识别，掩码回写防线不受影响；
     * 前端编辑保存后历史明文行会被正常加密迁移（见 {@code update_maskedOverLegacyPlaintext_*} 用例）。</p>
     */
    private void maskForResponse(NotifyChannel channel) {
        if (channel == null || !StringUtils.hasText(channel.getChannelConfig())) {
            return;
        }
        try {
            JSONObject cfg = JSONUtil.parseObj(channel.getChannelConfig());
            String pwd = cfg.getStr("password");
            if (StringUtils.hasText(pwd)) {
                cfg.set("password", pwd.startsWith(ENC_PREFIX) ? toSecretMask(pwd) : MASK_ONLY);
                channel.setChannelConfig(cfg.toString());
            }
        } catch (Exception e) {
            // 非 JSON 配置：原样返回（无 password 字段可脱敏）
        }
    }

    /**
     * 是否掩码格式（固定掩码 {@code "****"}，或 toSecretMask 的首4+****+末4 输出）。
     */
    private boolean isMaskedPassword(String value) {
        return value != null && (MASK_ONLY.equals(value) || MASK_PATTERN.matcher(value).matches());
    }

    /**
     * 生成密钥掩码（首 4 + **** + 末 4）。长度不足 8 时回退 {@link #MASK_ONLY}。
     *
     * <p>调用约束：<strong>只允许对 {@code enc:} 密文调用</strong>（密文片段无 KEK 不可利用）；
     * 历史明文行须直接用 {@link #MASK_ONLY}，不得调用本方法（否则泄漏明文首末各 4 位）。</p>
     */
    private String toSecretMask(String secret) {
        if (secret == null || secret.length() < 8) {
            return MASK_ONLY;
        }
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }
}
