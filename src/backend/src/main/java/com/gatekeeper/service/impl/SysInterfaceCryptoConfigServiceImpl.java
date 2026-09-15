package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gatekeeper.entity.SysInterfaceCryptoConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.SysInterfaceCryptoConfigMapper;
import com.gatekeeper.service.SysInterfaceCryptoConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 接口信息加密开关服务实现 — T17
 *
 * <h3>为什么带 10s 内存缓存</h3>
 * <p>本开关被<b>每一次接口/参数的读写</b>查询（列表、详情、参数树、网关路由后的解密），
 * 若不缓存会平白给每个请求增加一次 DB 往返。它是「极少变、必须快读到」的配置：</p>
 * <ul>
 *   <li>本实例写入后立即 {@link #invalidate()}（点保存马上生效）；</li>
 *   <li>多实例部署时其余实例最长 10s 收敛 —— 用 TTL 换掉分布式缓存的复杂度。</li>
 * </ul>
 *
 * <h3>fail-safe 方向（本特性的铁律）</h3>
 * <p>读不到开关时返回 {@code true}（<b>保持加密</b>）。方向若反了，
 * 一次 DB 抖动会让平台静默转为明文存储 —— 正是本特性要防的事。
 * 对照：{@code SysAccessWhitelistHandler} 是「准入」闸门，异常时 fail-open 放行；
 * <b>「保护」闸门与「准入」闸门的 fail-safe 方向天然相反</b>，不要照抄。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysInterfaceCryptoConfigServiceImpl implements SysInterfaceCryptoConfigService {

    /** 内存缓存 TTL（毫秒）；多实例部署时决定跨实例生效的最长延迟 */
    static final long CACHE_TTL_MS = 10_000L;

    private final SysInterfaceCryptoConfigMapper mapper;

    /** 缓存值：null 表示未缓存；true=启用；false=已关闭 */
    private volatile Boolean cachedEnabled;

    /** 缓存写入时刻（System.currentTimeMillis） */
    private volatile long cachedAt;

    @Override
    public boolean isEnabled() {
        long now = System.currentTimeMillis();
        Boolean cached = cachedEnabled;
        if (cached != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cached;
        }
        try {
            SysInterfaceCryptoConfig row = mapper.selectById(SysInterfaceCryptoConfig.SINGLETON_ID);
            // 缺行 ⇒ 视为启用（脚本刻意不播种；实体为 null 或 enabled 列为 null 时同样按启用）
            boolean enabled = !(row != null
                    && row.getEnabled() != null
                    && row.getEnabled().intValue() == SysInterfaceCryptoConfig.ENABLED_OFF);
            cachedEnabled = enabled;
            cachedAt = now;
            return enabled;
        } catch (Exception e) {
            // fail-safe：读不到开关时保持加密，绝不因为一次 DB 抖动把平台打成明文
            log.warn("读取接口信息加密开关失败，本次按「启用」处理（fail-safe 保持加密）: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public SysInterfaceCryptoConfig get() {
        SysInterfaceCryptoConfig row = mapper.selectById(SysInterfaceCryptoConfig.SINGLETON_ID);
        if (row != null) {
            return row;
        }
        // 缺行 ⇒ 返回 enabled=1 的虚拟行（不落库）
        SysInterfaceCryptoConfig virtual = new SysInterfaceCryptoConfig();
        virtual.setId(SysInterfaceCryptoConfig.SINGLETON_ID);
        virtual.setEnabled(SysInterfaceCryptoConfig.ENABLED_ON);
        return virtual;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysInterfaceCryptoConfig updateEnabled(Integer enabled, Long updatedBy, String remark) {
        if (enabled == null
                || (enabled.intValue() != SysInterfaceCryptoConfig.ENABLED_ON
                    && enabled.intValue() != SysInterfaceCryptoConfig.ENABLED_OFF)) {
            throw GatewayException.badRequest("enabled 只能为 1（启用）或 0（关闭）");
        }
        SysInterfaceCryptoConfig exist = mapper.selectById(SysInterfaceCryptoConfig.SINGLETON_ID);
        LocalDateTime now = LocalDateTime.now();
        if (exist == null) {
            SysInterfaceCryptoConfig row = new SysInterfaceCryptoConfig();
            row.setId(SysInterfaceCryptoConfig.SINGLETON_ID);
            row.setEnabled(enabled);
            row.setRemark(remark);
            row.setUpdatedBy(updatedBy);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            mapper.insert(row);
        } else {
            // 显式 set：remark 允许被清空；updateById 的 NOT_NULL 策略写不进 null
            // （同 T15-4 白名单更新 / T16-1 开关，见 docs/CONTRACTS §16.2）
            mapper.update(null, new UpdateWrapper<SysInterfaceCryptoConfig>()
                    .eq("id", SysInterfaceCryptoConfig.SINGLETON_ID)
                    .set("enabled", enabled)
                    .set("remark", remark)
                    .set("updated_by", updatedBy)
                    .set("updated_at", now));
        }
        invalidate();
        log.info("接口信息加密开关已更新: enabled={}（0=明文落库且全量可见）, by={}", enabled, updatedBy);
        return get();
    }

    @Override
    public void invalidate() {
        cachedEnabled = null;
        cachedAt = 0L;
    }
}
