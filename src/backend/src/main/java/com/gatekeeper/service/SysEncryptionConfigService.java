package com.gatekeeper.service;

import com.gatekeeper.entity.SysEncryptionConfig;

/**
 * 平台级加解密总开关服务 — T16-1
 *
 * <p>两个关注点分开：
 * <ul>
 *   <li>{@link #isGloballyEnabled()} —— 供<b>网关热路径</b>调用，带短 TTL 内存缓存，
 *       且查询失败时 <b>fail-safe 返回 true（保持加密）</b>；</li>
 *   <li>{@link #get()} / {@link #updateEnabled(Integer, Long, String)} —— 供管理页读写。</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T16-1 (2026-09-15)
 */
public interface SysEncryptionConfigService {

    /**
     * 平台加解密是否启用（网关热路径入口）。
     *
     * <p>返回 {@code false} 表示「全局强制明文」，调用方应跳过整条加解密配置解析链。</p>
     *
     * <p><b>fail-safe 方向与访问白名单相反</b>：白名单读不到时放行（fail-open），
     * 因为白名单是"防误拦"；开关读不到时必须<b>保持加密</b>，因为它是"降级为明文"的闸门 ——
     * 不能因为一次 DB 抖动就把全平台打成明文。</p>
     *
     * @return true=启用（含缺行、查询异常）；false=已显式关闭
     */
    boolean isGloballyEnabled();

    /**
     * 读取当前配置（管理页回显）。
     *
     * <p>缺行时返回一个 {@code enabled=1} 的<b>虚拟行</b>（id=1，不落库），
     * 让前端无需为「从未配置过」单独写分支。</p>
     *
     * @return 当前配置（非 null）
     */
    SysEncryptionConfig get();

    /**
     * 更新总开关（upsert 到 id=1）。
     *
     * @param enabled   1=启用；0=全局强制明文（其它值拒绝）
     * @param updatedBy 操作人 id，可为 null
     * @param remark    备注，可为 null（显式写入，允许清空）
     * @return 更新后的配置
     */
    SysEncryptionConfig updateEnabled(Integer enabled, Long updatedBy, String remark);

    /** 使内存缓存立即失效（写后调用；多实例部署时其余实例靠 TTL 收敛） */
    void invalidate();
}
