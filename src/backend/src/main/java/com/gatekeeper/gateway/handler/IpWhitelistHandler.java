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
 * Step 2: IP 白名单校验（应用级）
 *
 * <p>责任链第 2 环（位于 AppAuthHandler 之后，依赖其解析出的 appId）：
 * 查询应用配置的 IP 白名单（app_ip_whitelist 表），
 * 校验客户端真实 IP 是否命中（支持单 IP 与 CIDR 网段）。
 * 白名单为空表示不限制；白名单不为空时仅名单内 IP 可访问。</p>
 *
 * <p><b>T15-4 修复：只取启用条目（status=1）</b>。
 * 此前查询不带 status 条件，导致**被停用的条目依然在拦人** ——
 * 用户想把某段 IP 临时从白名单摘掉，只能删除记录，
 * 既丢失原始备注，也容易在事后想恢复时写错。
 * 停用语义改为"不参与校验"后，{@code status} 才真正可用。</p>
 *
 * <p><b>刻意不按 env_code 过滤</b>：该列是归类标注维度。
 * 白名单是强语义（要么放行要么拦死），误拦的代价远高于误放；
 * 若按环境过滤，A 环境配的行会拦掉 B 环境的正常调用，属典型误伤。
 * 详见 {@code app_ip_whitelist} 建表注释与 {@code docs/sql/t15-4-whitelist.sql}。</p>
 *
 * <p>与 {@code SysAccessWhitelistHandler}（系统级，@Order(0)）是「且」的关系：
 * 先过系统级（能不能进这个系统），再过本环节（能不能调这个应用）。</p>
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
        // 查询该应用**启用中**的白名单规则（停用条目不参与校验）
        List<AppIpWhitelist> whitelist = ipWhitelistMapper.selectList(
                new QueryWrapper<AppIpWhitelist>()
                        .eq("app_id", ctx.getAppId())
                        .eq("status", AppIpWhitelist.STATUS_ENABLED)
        );
        if (whitelist == null || whitelist.isEmpty()) {
            return; // 无启用的白名单规则，不限制
        }
        String clientIp = ctx.getClientIp();
        // 任一规则命中即放行（支持 CIDR 网段匹配，如 192.168.1.0/24）
        boolean allowed = clientIp != null && whitelist.stream()
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
