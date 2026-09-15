package com.gatekeeper.service;

import com.gatekeeper.entity.ApiGroupEncryptionConfig;
import com.gatekeeper.gateway.dto.EffectiveGroupEncryption;

/**
 * 接口分组加解密配置服务 — T15-1
 *
 * <p>维护入口唯一（分组页抽屉），接口侧只读展示「生效结果」。
 * 生效解析统一走 {@code com.gatekeeper.gateway.EncryptionConfigResolver}。</p>
 *
 * @author GateKeeper
 * @since T15-1 (2026-09-15)
 */
public interface ApiGroupEncryptionConfigService {

    /**
     * 查询某分组的**生效**加解密配置（含沿分组树向上继承）。
     *
     * @param groupId 分组 ID
     * @return 生效配置；整条父链无配置时返回 null（调用方视为"回退应用级"）
     */
    EffectiveGroupEncryption getEffective(Long groupId);

    /**
     * 查询某分组**自己的**配置行（不含继承），供编辑回填。
     *
     * @param groupId 分组 ID
     * @return 本分组配置行；未配置返回 null
     */
    ApiGroupEncryptionConfig getOwn(Long groupId);

    /**
     * 新建 / 覆盖某分组的配置（全量覆盖语义：先删后插，避免 MyBatis-Plus
     * {@code updateById} 的 NOT_NULL 策略导致"清空算法"写不进去）。
     *
     * @param groupId 分组 ID
     * @param config  配置（mode 决定三态）
     */
    void upsert(Long groupId, ApiGroupEncryptionConfig config);

    /**
     * 按配置行 ID 更新（整行全量覆盖）。
     *
     * @param id     配置行 ID
     * @param config 新值
     */
    void updateById(Long id, ApiGroupEncryptionConfig config);

    /**
     * 清除某分组自己的配置（清除后回落到父级继承，若有）。
     *
     * @param id 配置行 ID
     */
    void deleteById(Long id);
}
