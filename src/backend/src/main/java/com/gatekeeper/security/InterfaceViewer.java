package com.gatekeeper.security;

import lombok.Data;

/**
 * 「当前请求者能否看到接口明文」的判定上下文 — T17
 *
 * <p><b>为什么要有这个对象</b>：接口列表一页 N 行，若按行去查白名单/角色，
 * 一次分页会产生 O(N) 次 DB 往返。本对象把「与行无关」的部分（开关、白名单命中、
 * 是否超管、当前 uid）<b>每次请求解析一次</b>，之后每一行只需一次
 * {@link #canSee(Long)} 的纯内存判断。</p>
 *
 * <p>判定顺序见 {@link #canSee(Long)}；口径与 {@code SysInterfaceVisibility} 类注释一致。</p>
 *
 * <h3>fail-safe 方向</h3>
 * <p>本对象由 {@code InterfaceVisibilityServiceImpl.resolveViewer()} 构造。
 * 白名单/角色查询<b>异常时按「不可见」构造</b>（{@code whitelistEmpty=true}、其余 false）——
 * 这是「保护类闸门」的方向：宁可暂时不给看，也不能因一次配置面故障把明文放出去。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
@Data
public class InterfaceViewer {

    /**
     * 构造一个「关闭保护」的 viewer：开关关闭时用它，
     * 之后 {@link #canSee(Long)} 恒为 true。
     */
    public static InterfaceViewer unprotected() {
        InterfaceViewer v = new InterfaceViewer();
        v.setProtectionEnabled(false);
        return v;
    }

    /**
     * 构造一个「解析失败，一律掩码」的 viewer（fail-safe）。
     */
    public static InterfaceViewer failSafeMaskAll() {
        InterfaceViewer v = new InterfaceViewer();
        v.setProtectionEnabled(true);
        v.setSuperAdmin(false);
        v.setWhitelistEmpty(true);
        v.setUserHit(false);
        v.setRoleHit(false);
        return v;
    }

    /** 加密开关是否启用；false ⇒ 一切明文可见（不掩码） */
    private boolean protectionEnabled;

    /** 是否 SUPER_ADMIN（兜底放行，防「把所有人挡在门外」的死锁） */
    private boolean superAdmin;

    /** 当前登录用户 id（可能为 null：非 HTTP 上下文 / 未登录） */
    private Long uid;

    /** 白名单（status=1）是否为空 */
    private boolean whitelistEmpty;

    /** 是否命中 (USER, uid) */
    private boolean userHit;

    /** 是否命中 (ROLE, 当前用户的任一角色) */
    private boolean roleHit;

    /**
     * 当前请求者能否看到该接口的明文。
     *
     * @param ownerId 该行接口的负责人 id（{@code api_interface.owner_id}），可为 null
     * @return true=返回明文；false=必须返回掩码
     */
    public boolean canSee(Long ownerId) {
        if (!protectionEnabled) {
            return true;
        }
        if (superAdmin) {
            return true;
        }
        if (uid != null && ownerId != null && uid.equals(ownerId)) {
            return true;
        }
        if (whitelistEmpty) {
            return false; // 未配置白名单 ⇒ 只认超管与 owner
        }
        return userHit || roleHit;
    }

    /** 便捷取反：是否需要掩码 */
    public boolean mustMask(Long ownerId) {
        return !canSee(ownerId);
    }
}
