package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gatekeeper.entity.SysEncryptionConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.SysEncryptionConfigMapper;
import com.gatekeeper.service.SysEncryptionConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 平台级加解密总开关服务实现 — T16-1
 *
 * <h3>为什么网关热路径要带缓存</h3>
 * <p>网关每次调用本就要查 {@code api_encryption_config} 与 {@code app_encryption_config}；
 * 再无条件多查一行总开关会平白增加一次 DB 往返。总开关是「极少变、必须快读到」的配置，
 * 故用 <b>volatile 值 + 10s TTL</b> 的内存缓存：</p>
 * <ul>
 *   <li>本实例写入后立即 {@link #invalidate()}（用户点保存马上生效）；</li>
 *   <li>多实例部署时其余实例最长 10s 后收敛 —— 用 TTL 换掉分布式缓存的复杂度，
 *       对"安全总闸"这种低频操作完全够用。</li>
 * </ul>
 *
 * <h3>fail-safe 方向（与访问白名单相反，务必区分）</h3>
 * <p>{@code SysAccessWhitelistHandler} 查询异常时 <b>放行（fail-open）</b>，因为它防的是"误拦"；
 * 本开关是「把全平台降级为明文」的闸门，查询异常时必须 <b>保持加密（fail-safe）</b>：
 * 读不到开关就当它是启用的。方向反了会在 DB 抖动时静默泄漏明文。</p>
 *
 * @author GateKeeper
 * @since T16-1 (2026-09-15)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysEncryptionConfigServiceImpl implements SysEncryptionConfigService {

    /** 内存缓存 TTL（毫秒）；多实例部署时决定跨实例生效的最长延迟 */
    static final long CACHE_TTL_MS = 10_000L;

    private final SysEncryptionConfigMapper mapper;

    /** 缓存值：null 表示未缓存；true=启用；false=全局明文 */
    private volatile Boolean cachedEnabled;

    /** 缓存写入时刻（System.currentTimeMillis） */
    private volatile long cachedAt;

    @Override
    public boolean isGloballyEnabled() {
        long now = System.currentTimeMillis();
        Boolean cached = cachedEnabled;
        if (cached != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cached;
        }
        try {
            SysEncryptionConfig row = mapper.selectById(SysEncryptionConfig.SINGLETON_ID);
            // 缺行 ⇒ 视为启用：脚本刻意不播种，存量库与新建库行为一致（零迁移）
            boolean enabled = !(row != null
                    && row.getEnabled() != null
                    && row.getEnabled().intValue() == SysEncryptionConfig.ENABLED_OFF);
            cachedEnabled = enabled;
            cachedAt = now;
            return enabled;
        } catch (Exception e) {
            // fail-safe：读不到开关时保持加密，绝不因为一次 DB 抖动把全平台打成明文
            log.warn("读取平台加解密总开关失败，本次按「启用」处理（fail-safe 保持加密）: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public SysEncryptionConfig get() {
        SysEncryptionConfig row = mapper.selectById(SysEncryptionConfig.SINGLETON_ID);
        if (row != null) {
            return row;
        }
        // 缺行 ⇒ 返回 enabled=1 的虚拟行（不落库），前端无需为"从未配置"写分支
        SysEncryptionConfig virtual = new SysEncryptionConfig();
        virtual.setId(SysEncryptionConfig.SINGLETON_ID);
        virtual.setEnabled(SysEncryptionConfig.ENABLED_ON);
        return virtual;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysEncryptionConfig updateEnabled(Integer enabled, Long updatedBy, String remark) {
        if (enabled == null
                || (enabled.intValue() != SysEncryptionConfig.ENABLED_ON
                    && enabled.intValue() != SysEncryptionConfig.ENABLED_OFF)) {
            throw GatewayException.badRequest("enabled 只能为 1（启用）或 0（全局强制明文）");
        }
        SysEncryptionConfig exist = mapper.selectById(SysEncryptionConfig.SINGLETON_ID);
        LocalDateTime now = LocalDateTime.now();
        if (exist == null) {
            SysEncryptionConfig row = new SysEncryptionConfig();
            row.setId(SysEncryptionConfig.SINGLETON_ID);
            row.setEnabled(enabled);
            row.setRemark(remark);
            row.setUpdatedBy(updatedBy);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            mapper.insert(row);
        } else {
            // 显式 set：remark 允许被清空；updateById 的 NOT_NULL 策略写不进 null
            // （同 T15-4 白名单更新，见 docs/CONTRACTS §16.2）
            mapper.update(null, new UpdateWrapper<SysEncryptionConfig>()
                    .eq("id", SysEncryptionConfig.SINGLETON_ID)
                    .set("enabled", enabled)
                    .set("remark", remark)
                    .set("updated_by", updatedBy)
                    .set("updated_at", now));
        }
        invalidate();
        log.info("平台加解密总开关已更新: enabled={}（0=全局强制明文）, by={}", enabled, updatedBy);
        return get();
    }

    @Override
    public void invalidate() {
        cachedEnabled = null;
        cachedAt = 0L;
    }
}
