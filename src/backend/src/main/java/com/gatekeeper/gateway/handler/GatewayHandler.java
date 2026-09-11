package com.gatekeeper.gateway.handler;

import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;

/**
 * 网关请求处理器接口 - 责任链模式
 *
 * <p>所有处理环节（IP 白名单、封禁检查、应用认证、限流、权限、解密、转发）都实现本接口，
 * 通过 {@code @Order} 注解决定执行顺序，由 {@code GatewayCore} 统一调度。</p>
 */
public interface GatewayHandler {

    /**
     * 处理请求。校验/处理通过则正常返回，让链路继续向后执行；
     * 不通过则抛出 {@link GatewayException} 中断链路。
     *
     * @param ctx 网关上下文（前序 Handler 的处理结果也存放在其中）
     * @throws GatewayException 如果校验不通过，抛出异常中断链路
     */
    void handle(GatewayContext ctx);

    /**
     * 获取处理器顺序（数字越小越先执行）
     */
    default int getOrder() {
        return 0;
    }
}
