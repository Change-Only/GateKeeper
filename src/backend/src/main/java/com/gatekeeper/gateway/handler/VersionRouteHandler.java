package com.gatekeeper.gateway.handler;

import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.entity.ApiVersion;
import com.gatekeeper.gateway.EnvResolver;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.gateway.router.GrayscaleRouter;
import com.gatekeeper.service.ApiVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Step 6: 灰度版本路由（数据面版本拆分）
 *
 * <p>责任链第 6 环：在权限校验（PermissionHandler, &#64;Order(5)）之后、转发（ForwardHandler）之前，
 * 依据 api_version 表为本次请求选择目标版本：</p>
 * <ol>
 *   <li>取 {@code ctx.interfaceId}（由 PermissionHandler 写入）；</li>
 *   <li>调 {@code apiVersionService.list(interfaceId)} 拉取该接口全部版本（返回 DTO 列表）；</li>
 *   <li>将 DTO 转为实体后，用 {@link GrayscaleRouter} 基于 appId 稳定哈希做灰度分流，写入 {@code ctx.version}；</li>
 *   <li>用 {@link EnvResolver} 解析环境码，写入 {@code ctx.envCode}（已存在则不覆盖）。</li>
 * </ol>
 * <p><b>FAIL-OPEN</b>：任何异常（无版本、服务异常、Redis 故障等）均只记录日志、保留默认值并继续，
 * 绝不抛出，保证灰度组件自身故障不影响主链路。</p>
 *
 * @author GateKeeper
 * @since T04-B (APIM V2 网关灰度路由)
 */
@Slf4j
@Component
@Order(6)
@RequiredArgsConstructor
public class VersionRouteHandler implements GatewayHandler {

    private final ApiVersionService apiVersionService;
    private final EnvResolver envResolver;

    @Override
    public void handle(GatewayContext ctx) {
        Long interfaceId = ctx.getInterfaceId();
        if (interfaceId == null) {
            // 前置环节未解析出接口（理论上 PermissionHandler 已保证），跳过灰度，保留默认
            log.debug("Version routing skipped: interfaceId is null");
            return;
        }

        try {
            // 注意：T03b 的 ApiVersionService.list 返回 List<ApiVersionDto>，需转为实体供纯路由器消费
            List<ApiVersionDto> dtos = apiVersionService.list(interfaceId);
            if (dtos == null || dtos.isEmpty()) {
                // 无版本配置：保留默认（current/空），fail-open 继续
                log.debug("Version routing: no versions configured for interfaceId={}", interfaceId);
                return;
            }

            List<ApiVersion> versions = toEntities(dtos);
            long stableHash = stableHash(ctx.getAppId());
            String chosen = GrayscaleRouter.chooseVersion(versions, stableHash);
            if (chosen != null) {
                ctx.setVersion(chosen);
            }

            String resolvedEnv = envResolver.resolve(ctx);
            ctx.setEnvCode(ctx.getEnvCode() != null ? ctx.getEnvCode() : resolvedEnv);

            log.debug("Version routing: interfaceId={}, appId={}, -> version={}, env={}",
                    interfaceId, ctx.getAppId(), chosen, ctx.getEnvCode());
        } catch (Exception e) {
            // FAIL-OPEN：灰度路由异常不得中断主链路
            log.error("Version routing failed (fail-open) for interfaceId={}: {}", interfaceId, e.getMessage(), e);
        }
    }

    /**
     * 将 T03b 版本 DTO 列表转为实体列表（供纯逻辑路由器 {@link GrayscaleRouter} 消费）。
     * DTO 与 Entity 字段一一对应，此处仅做安全拷贝，跳过 null 元素。
     */
    private List<ApiVersion> toEntities(List<ApiVersionDto> dtos) {
        List<ApiVersion> versions = new ArrayList<>(dtos.size());
        for (ApiVersionDto d : dtos) {
            if (d == null) {
                continue;
            }
            ApiVersion v = new ApiVersion();
            v.setId(d.getId());
            v.setApiId(d.getApiId());
            v.setVersion(d.getVersion());
            v.setStatus(d.getStatus());
            v.setIsCurrent(d.getIsCurrent());
            v.setGrayRatio(d.getGrayRatio());
            v.setChangeLog(d.getChangeLog());
            v.setDeprecateTime(d.getDeprecateTime());
            v.setOfflinePlanTime(d.getOfflinePlanTime());
            v.setCreatedAt(d.getCreatedAt());
            v.setUpdatedAt(d.getUpdatedAt());
            versions.add(v);
        }
        return versions;
    }

    /**
     * 由 appId 派生的稳定哈希：同一 appId 始终得到同一哈希值，保证灰度分流在会话内稳定。
     */
    private long stableHash(Long appId) {
        if (appId == null) {
            return 0L;
        }
        return (long) String.valueOf(appId).hashCode();
    }
}
