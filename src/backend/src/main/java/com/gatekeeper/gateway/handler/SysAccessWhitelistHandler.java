package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.SysIpWhitelist;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.SysIpWhitelistMapper;
import com.gatekeeper.util.IpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Step 0: 系统级访问白名单校验（网关入口全局前置）— T15-4
 *
 * <p>责任链<b>最前一环</b>（{@code @Order(0)}，先于 {@link AppAuthHandler}）。
 * 语义是「本系统的入口只对这些来源开放」，因此它<b>不区分应用</b>：
 * 一旦存在启用条目，<b>任何</b>应用的接口都只接受名单内的来源 IP。</p>
 *
 * <p><b>与 {@link IpWhitelistHandler}（应用级，@Order(2)）的关系</b>：
 * 两层是「且」的关系 ——
 * 先过系统级（能不能进这个系统），再过应用级（能不能调这个应用）。
 * 二者配置面、权限点、维护入口都不同，故意不合并：
 * 合并后无法表达"公司内网整体开放，但某合作方应用只允许其专线出口"。</p>
 *
 * <p><b>三条行为约定（改动前请先读 {@code sys_ip_whitelist} 的建表注释）</b>：
 * <ol>
 *   <li><b>表空 / 无启用行 ⇒ 不限制</b>。这是本改造对存量环境零影响的关键，
 *       也避免迁移一跑就把测试机或管理员自己挡在门外；</li>
 *   <li><b>查询异常 ⇒ fail-open 放行 + WARN</b>。与既有 Redis 降级取向一致：
 *       配置面故障不应该升级为"整个网关不可用"；</li>
 *   <li>查询用 {@code selectList} 而非 {@code selectOne}，且不假设条数 —— 白名单天然是多行。</li>
 * </ol></p>
 *
 * <p>拦截时置 {@code blocked/blockReason} 后再抛 403，与 {@link IpWhitelistHandler} 一致，
 * 这样 {@code LogHandler} 的调用日志与安全检测（异常 IP 检测）能正常拿到上下文。</p>
 *
 * @author GateKeeper
 * @since T15-4 (2026-09-15)
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class SysAccessWhitelistHandler implements GatewayHandler {

    private final SysIpWhitelistMapper sysIpWhitelistMapper;

    /**
     * 校验来源 IP 是否通过系统级访问白名单。
     *
     * @param ctx 网关上下文（{@code clientIp} 已在 {@code GatewayContext.from} 中填好，本环节无需前置依赖）
     */
    @Override
    public void handle(GatewayContext ctx) {
        List<SysIpWhitelist> enabled;
        try {
            enabled = sysIpWhitelistMapper.selectList(
                    new QueryWrapper<SysIpWhitelist>().eq("status", SysIpWhitelist.STATUS_ENABLED));
        } catch (Exception e) {
            // fail-open：配置面读不出来时放行，绝不因此把整个网关打成不可用
            log.warn("查询系统访问白名单失败，本次放行（fail-open）: {}", e.getMessage());
            return;
        }
        if (enabled == null || enabled.isEmpty()) {
            return; // 未启用系统级白名单 ⇒ 不限制（与改造前行为一致）
        }

        String clientIp = ctx.getClientIp();
        boolean allowed = clientIp != null && enabled.stream()
                .anyMatch(w -> IpUtil.isIpInCidr(clientIp, w.getIpCidr()));
        if (!allowed) {
            ctx.setBlocked(true);
            ctx.setBlockReason("IP not in system whitelist");
            log.warn("系统访问白名单拦截: clientIp={}, path={}, 启用规则数={}",
                    clientIp, ctx.getPath(), enabled.size());
            throw GatewayException.forbidden("IP " + clientIp + " 不在系统访问白名单内");
        }
        log.debug("System access whitelist passed: ip={}, path={}", clientIp, ctx.getPath());
    }
}
