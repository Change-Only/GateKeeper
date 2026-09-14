package com.gatekeeper.service;

import com.gatekeeper.dto.InterfaceTestRequest;
import com.gatekeeper.dto.InterfaceTestResult;

import javax.servlet.http.HttpServletRequest;

/**
 * 接口试调服务 — T13「新增接口测试功能」
 *
 * <p>用户需求（2026-09-14 第 2 条）："新增接口测试功能"。定位是**排障与验证工具**，
 * 而不是又一个调用入口，所以刻意同时支持两条路径，覆盖两种典型问题：</p>
 * <ul>
 *   <li>{@link InterfaceTestRequest#MODE_DIRECT} 直连后端：绕过网关，直接打「生效环境配置的服务前缀 + 接口URI」。
 *       用来回答"上游服务本身通不通、我配的前缀对不对"—— 没有应用凭证也能用。</li>
 *   <li>{@link InterfaceTestRequest#MODE_GATEWAY} 走网关：用真实应用凭证按网关契约签名后调用
 *       {@code /gateway/**}，把 鉴权 → 限流 → 权限 → Mock → 转发 整条责任链跑一遍。
 *       用来回答"这个应用到底能不能调通这个接口"。</li>
 * </ul>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
public interface InterfaceTestService {

    /**
     * 对指定接口发起一次试调。
     *
     * @param apiId       接口 ID（必填）
     * @param req         试调参数（模式 / 环境 / 应用 / 请求体 / 额外头），可为 null（等价全默认：直连 + 默认环境）
     * @param httpRequest 当前 Web 请求（走网关模式据此推导自身 base url 与 context-path；可为 null）
     * @return 试调结果（永不返回 null；网络失败也会带回 error 而非抛异常）
     */
    InterfaceTestResult test(Long apiId, InterfaceTestRequest req, HttpServletRequest httpRequest);
}
