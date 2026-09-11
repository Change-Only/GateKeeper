package com.gatekeeper.gateway.handler;

import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.security.banner.IpBanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Step 3: IP 封禁检查
 *
 * <p>责任链第 3 环（位于 AppAuthHandler 之后，依赖其解析出的 appId）：
 * 从 Redis 查询客户端 IP 是否被封禁（O(1) 实时生效）。
 * 先查应用级封禁（ip_ban:{ip}:{appId}），再查全局封禁（ip_ban:{ip}:global）。
 * 封禁记录以 TTL 实现到期自动解封。</p>
 *
 * <p><b>故障降级</b>：Redis 不可用时封禁检查降级放行（fail-open），
 * 宁可暂时失去封禁拦截能力，也不让 Redis 单点故障拖垮整个网关。
 * 降级受 {@code gatekeeper.redis.fail-open}（默认 true）开关控制。</p>
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class IpBanCheckHandler implements GatewayHandler {

    private final StringRedisTemplate redisTemplate;
    private final IpBanService ipBanService;

    /** Redis 故障降级开关：true=降级放行（默认），false=故障时按未封禁处理（同放行语义） */
    @org.springframework.beans.factory.annotation.Value("${gatekeeper.redis.fail-open:true}")
    private boolean redisFailOpen;

    /**
     * 检查客户端 IP 是否处于封禁状态
     *
     * @param ctx 网关上下文
     */
    @Override
    public void handle(GatewayContext ctx) {
        String ip = ctx.getClientIp();
        Long appId = ctx.getAppId();

        try {
            checkBan(ctx, ip, appId);
        } catch (GatewayException e) {
            throw e; // 命中封禁的业务拒绝原样抛出
        } catch (Exception e) {
            // Redis 异常：降级放行，封禁拦截暂时失效（业务链路不中断）
            if (redisFailOpen) {
                log.error("IP ban check degraded (fail-open) for ip={}: {}", ip, e.getMessage());
                return;
            }
            throw e;
        }
        log.debug("IP ban check passed for ip={}", ip);
    }

    /** 封禁查询与拦截判定（Redis 操作，异常由 handle 统一降级处理） */
    private void checkBan(GatewayContext ctx, String ip, Long appId) {
        // 检查应用级封禁：只封禁该 IP 对该应用的调用
        String banKey = "ip_ban:" + ip + ":" + (appId != null ? appId : "global");
        String banReason = redisTemplate.opsForValue().get(banKey);

        if (banReason == null && appId != null) {
            // 应用级无封禁记录时，再检查全局封禁（对所有应用的封禁）
            banKey = "ip_ban:" + ip + ":global";
            banReason = redisTemplate.opsForValue().get(banKey);
        }

        if (banReason != null) {
            ctx.setBlocked(true);
            ctx.setBlockReason("IP banned: " + banReason);
            // 查询封禁剩余 TTL，换算为解封时间返回给调用方
            Long ttl = redisTemplate.getExpire(banKey);
            String unbanTime = ttl != null && ttl > 0
                    ? LocalDateTime.now().plusSeconds(ttl).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : "未知";
            throw GatewayException.forbidden("IP " + ip + " 已被封禁，解封时间：" + unbanTime + "，原因：" + banReason);
        }
    }
}
