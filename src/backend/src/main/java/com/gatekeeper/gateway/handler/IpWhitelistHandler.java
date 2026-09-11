package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.App;
import com.gatekeeper.entity.AppIpWhitelist;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.AppIpWhitelistMapper;
import com.gatekeeper.util.IpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Step 2: IP 白名单校验
 *
 * <p>责任链第 2 环（位于 AppAuthHandler 之后，依赖其解析出的 appId）：
 * 查询应用配置的 IP 白名单（app_ip_whitelist 表），
 * 校验客户端真实 IP 是否命中（支持单 IP 与 CIDR 网段）。
 * 白名单为空表示不限制；白名单不为空时仅名单内 IP 可访问。</p>
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class IpWhitelistHandler implements GatewayHandler {

    private final AppIpWhitelistMapper ipWhitelistMapper;

    /**
     * 校验客户端 IP 是否在应用白名单内
     *
     * @param ctx 网关上下文（需已由 AppAuthHandler 写入 appId 与 clientIp）
     */
    @Override
    public void handle(GatewayContext ctx) {
        if (ctx.getAppId() == null) {
            return; // 应用未识别，由后续Handler处理
        }
        // 查询该应用配置的白名单规则
        List<AppIpWhitelist> whitelist = ipWhitelistMapper.selectList(
                new QueryWrapper<AppIpWhitelist>().eq("app_id", ctx.getAppId())
        );
        if (whitelist == null || whitelist.isEmpty()) {
            return; // 白名单为空，不限制
        }
        String clientIp = ctx.getClientIp();
        // 任一规则命中即放行（支持 CIDR 网段匹配，如 192.168.1.0/24）
        boolean allowed = whitelist.stream()
                .anyMatch(w -> IpUtil.isIpInCidr(clientIp, w.getIpCidr()));
        if (!allowed) {
            // 标记拦截并抛出异常，中断链路返回 403
            ctx.setBlocked(true);
            ctx.setBlockReason("IP not in whitelist");
            throw GatewayException.forbidden("IP " + clientIp + " 不在白名单内");
        }
        log.debug("IP whitelist passed for app={}, ip={}", ctx.getAppId(), clientIp);
    }
}
