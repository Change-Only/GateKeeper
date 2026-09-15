package com.gatekeeper.service;

import com.gatekeeper.entity.SysInterfaceCryptoConfig;

/**
 * 接口信息加密开关服务 — T17
 *
 * <p>与「网关对外报文加解密」是<b>两回事</b>，勿混淆：那条链路（接口级 / 分组级 / 应用级
 * 三档优先级，见 {@code gateway.handler.EncryptionHandler}）处理的是第三方调我方接口时
 * 请求体与响应体的加解密；本服务管的是「平台自身把接口路径 / 参数契约落库时是否加密，
 * 以及控制台里给谁看明文」，属于<b>存储与展示侧</b>的保护。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
public interface SysInterfaceCryptoConfigService {

    /**
     * 接口信息加密是否启用（读写热路径入口）。
     *
     * <p>返回 {@code false} 表示「明文落库 + 控制台全量可见」。</p>
     *
     * <p><b>fail-safe 方向 = 保持加密</b>：读不到开关（DB 抖动/表缺失）时返回 {@code true}。
     * 这是「保护类闸门」的方向 —— 一次 DB 抖动绝不能静默把平台打成明文；
     * 注意与「准入类闸门」{@code sys_ip_whitelist} 的 fail-open <b>正好相反</b>
     * （那个怕误拦，这个怕误放）。</p>
     *
     * @return true=启用（含缺行、查询异常）；false=已显式关闭
     */
    boolean isEnabled();

    /**
     * 读取当前配置（管理页回显）。
     *
     * <p>缺行时返回 {@code enabled=1} 的<b>虚拟行</b>（id=1，不落库），
     * 前端无需为「从未配置」单独写分支。</p>
     *
     * @return 当前配置（非 null）
     */
    SysInterfaceCryptoConfig get();

    /**
     * 更新开关（upsert 到 id=1）。
     *
     * @param enabled   1=启用；0=关闭（其它值拒绝）
     * @param updatedBy 操作人 id，可为 null
     * @param remark    备注，可为 null（显式写入，允许清空）
     * @return 更新后的配置
     */
    SysInterfaceCryptoConfig updateEnabled(Integer enabled, Long updatedBy, String remark);

    /** 使内存缓存立即失效（写后调用；多实例部署时其余实例靠 TTL 收敛） */
    void invalidate();
}
