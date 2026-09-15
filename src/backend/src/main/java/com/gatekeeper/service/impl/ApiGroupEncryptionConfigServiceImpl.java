package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiGroupEncryptionConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.EncryptionConfigResolver;
import com.gatekeeper.gateway.dto.EffectiveGroupEncryption;
import com.gatekeeper.mapper.ApiGroupEncryptionConfigMapper;
import com.gatekeeper.service.ApiGroupEncryptionConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 接口分组加解密配置服务实现 — T15-1
 *
 * <p>写入语义：**整行全量覆盖**。
 * MyBatis-Plus 的 {@code updateById} 采用 NOT_NULL 更新策略（null 字段不写库），
 * 而本域最常见的操作恰恰是「把 ENABLED 改成 DISABLED / INHERIT」——
 * 需要把算法、密钥等一起清成 null，用 updateById 会出现
 * 「看着关了加密，库里还留着旧密钥」的幽灵数据。故统一走「先删后插」。</p>
 *
 * @author GateKeeper
 * @since T15-1 (2026-09-15)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiGroupEncryptionConfigServiceImpl implements ApiGroupEncryptionConfigService {

    private final ApiGroupEncryptionConfigMapper groupEncMapper;
    private final EncryptionConfigResolver resolver;

    @Override
    public EffectiveGroupEncryption getEffective(Long groupId) {
        return resolver.resolveForGroup(groupId);
    }

    @Override
    public ApiGroupEncryptionConfig getOwn(Long groupId) {
        if (groupId == null) {
            return null;
        }
        List<ApiGroupEncryptionConfig> rows = groupEncMapper.selectList(
                new QueryWrapper<ApiGroupEncryptionConfig>().eq("group_id", groupId).orderByDesc("id"));
        return (rows == null || rows.isEmpty()) ? null : rows.get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void upsert(Long groupId, ApiGroupEncryptionConfig config) {
        if (groupId == null) {
            throw GatewayException.badRequest("分组ID不能为空");
        }
        if (config == null) {
            throw GatewayException.badRequest("配置不能为空");
        }
        String mode = EncryptionConfigResolver.normalizeMode(config.getMode());
        normalizeModePayload(mode, config);

        ApiGroupEncryptionConfig existing = getOwn(groupId);
        if (existing != null) {
            groupEncMapper.deleteById(existing.getId());
        }
        config.setId(null); // 让自增主键重新分配，避免复用已删 ID 造成歧义
        config.setGroupId(groupId);
        config.setMode(mode);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        groupEncMapper.insert(config);
        log.info("Group encryption config upserted: groupId={}, mode={}", groupId, mode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateById(Long id, ApiGroupEncryptionConfig config) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        ApiGroupEncryptionConfig existing = groupEncMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("分组加解密配置不存在");
        }
        if (config == null) {
            throw GatewayException.badRequest("配置不能为空");
        }
        String mode = EncryptionConfigResolver.normalizeMode(config.getMode());
        normalizeModePayload(mode, config);

        groupEncMapper.deleteById(id);
        config.setId(null);
        config.setGroupId(existing.getGroupId()); // 归属分组不可改
        config.setMode(mode);
        config.setCreatedAt(existing.getCreatedAt());
        config.setUpdatedAt(LocalDateTime.now());
        groupEncMapper.insert(config);
        log.info("Group encryption config updated: id={}, groupId={}, mode={}", id, existing.getGroupId(), mode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        ApiGroupEncryptionConfig existing = groupEncMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("分组加解密配置不存在");
        }
        groupEncMapper.deleteById(id);
        log.info("Group encryption config deleted: id={}, groupId={}", id, existing.getGroupId());
    }

    // =====================================================================
    // 内部：按 mode 归一 + 校验
    // =====================================================================

    /**
     * 按三态归一载荷并校验。
     *
     * <ul>
     *   <li>{@code ENABLED}：要求入参/返参至少开启一项，且开启的那一侧必须有算法与密钥
     *       —— 防止"配了加密却没有密钥"这种网关必然解密失败、且要到线上才暴露的配置。</li>
     *   <li>{@code DISABLED} / {@code INHERIT}：清空全部密钥材料，
     *       杜绝"看着关了/继承了，库里还留着旧密钥"的幽灵数据。</li>
     * </ul>
     */
    private void normalizeModePayload(String mode, ApiGroupEncryptionConfig c) {
        if (ApiGroupEncryptionConfig.MODE_ENABLED.equals(mode)) {
            boolean req = Boolean.TRUE.equals(c.getRequestEncrypted());
            boolean resp = Boolean.TRUE.equals(c.getResponseEncrypted());
            if (!req && !resp) {
                throw GatewayException.badRequest(
                        "选择「启用加解密」时，入参与返参至少要开启一项；若确实不需要加解密，请改选「不需要加解密」");
            }
            if (req) {
                requireText(c.getRequestAlgorithm(), "入参加密算法");
                requireText(c.getRequestKey(), "入参密钥");
            }
            if (resp) {
                requireText(c.getResponseAlgorithm(), "返参加密算法");
                requireText(c.getResponseKey(), "返参密钥");
            }
            return;
        }
        // DISABLED / INHERIT：不保留本层密钥材料
        c.setRequestEncrypted(false);
        c.setRequestAlgorithm(null);
        c.setRequestMode(null);
        c.setRequestKey(null);
        c.setRequestIv(null);
        c.setRequestPadding(null);
        c.setResponseEncrypted(false);
        c.setResponseAlgorithm(null);
        c.setResponseMode(null);
        c.setResponseKey(null);
        c.setResponseIv(null);
        c.setResponsePadding(null);
    }

    private void requireText(String v, String label) {
        if (!StringUtils.hasText(v)) {
            throw GatewayException.badRequest(label + "不能为空");
        }
    }
}
