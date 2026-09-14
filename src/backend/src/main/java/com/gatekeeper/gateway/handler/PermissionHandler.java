package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.entity.AppApiPermission;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.GatewayPaths;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.mapper.AppApiPermissionMapper;
import com.gatekeeper.security.SecurityDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Step 5: 接口查找 + 权限校验
 *
 * <p>责任链第 5 环：</p>
 * <ol>
 *   <li>按请求路径 + 请求方法 + 启用状态匹配已注册接口</li>
 *   <li>校验应用是否被授权调用该接口：优先查 app_api_grant（状态机 + 有效期 + 环境），
 *       未命中时回退到存量 app_api_permission 快照（兼容旧模型）</li>
 * </ol>
 * <p>接口不存在返回 404；未授权返回 403 并记录权限越界事件（供安全检测使用）。</p>
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class PermissionHandler implements GatewayHandler {

    private final ApiInterfaceMapper interfaceMapper;
    private final AppApiGrantMapper appApiGrantMapper;
    private final AppApiPermissionMapper permissionMapper;
    private final SecurityDetectionService securityDetectionService;

    /** 当 ctx 未携带环境时的默认环境 */
    private static final String DEFAULT_ENV = "prod";

    /**
     * 生效环境配置解析器（T13）。
     *
     * <p>字段注入 + {@code required=false}：解析器缺失或解析异常时**不阻断**链路，
     * 保持改造前的行为（用 {@code api_interface.backend_url} 转发），符合本项目
     * "新增能力 fail-open、绝不因新增依赖把既有流量掐死"的一贯口径。</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.gatekeeper.gateway.EnvConfigResolver envConfigResolver;

    /**
     * 解析生效环境配置，并把转发目标改写为「服务前缀 + 接口 URI」。
     *
     * <p>T13 把「用哪个上游」的决策收敛到 {@code EnvConfigResolver}：
     * <ul>
     *   <li>命中配置 ⇒ {@code ctx.backendUrl = 前缀 + 接口URI}，并把生效配置塞进上下文，
     *       ForwardHandler 据此决定超时/重试/Mock；</li>
     *   <li>没命中 ⇒ 保持 {@code api_interface.backend_url} 兜底（改造前行为，零回归）。</li>
     * </ul>
     * 同一请求内只解析一次，避免"页面显示继承自 A、网关实际走 B"的口径错位。</p>
     *
     * @param ctx 网关上下文
     * @param apiInterface 已匹配的接口实体
     */
    private void applyEffectiveEnvConfig(GatewayContext ctx, ApiInterface apiInterface) {
        if (envConfigResolver == null) {
            return;
        }
        try {
            com.gatekeeper.gateway.dto.EffectiveEnvConfig effective =
                    envConfigResolver.resolve(apiInterface, ctx.getEnvCode());
            ctx.setEffectiveEnvConfig(effective);
            String joined = com.gatekeeper.gateway.EnvConfigResolver.joinUrl(
                    effective.getUpstreamUrl(), apiInterface.getInterfacePath());
            if (joined != null) {
                ctx.setBackendUrl(joined);
                log.debug("Effective upstream applied: interface={}, env={}, target={}, source={}, mock={}",
                        apiInterface.getInterfacePath(), ctx.getEnvCode(), joined,
                        effective.getSourceType(), effective.isMockOn());
            }
        } catch (Exception e) {
            // 解析失败保持原 backendUrl（fail-open）
            log.warn("Resolve effective env config failed, keep interface backendUrl: {}", e.getMessage());
        }
    }

    /**
     * 查找接口并校验应用调用权限
     *
     * @param ctx 网关上下文（需已写入 appId；成功后写入 interfaceId/backendUrl/timeoutMs）
     */
    @Override
    public void handle(GatewayContext ctx) {
        // 按路径 + 方法 + 启用状态精确匹配接口。
        //
        // 🔴 T13 修正：ctx.getPath() 是**含 context-path 的原始 requestURI**（如 /api/gateway/test），
        // 直接拿去 eq("interface_path", ...) 永远匹配不上库里的 /test 或 /gateway/test
        // ⇒ 网关对任何接口都回 404「接口不存在或未启用」。
        // 这里改为「按候选路径逐个尝试，先精确后宽松」（见 GatewayPaths），兼容两种存量录入约定，
        // **不需要数据迁移**。
        ApiInterface apiInterface = null;
        List<String> candidates = GatewayPaths.interfacePathCandidates(ctx.getPath(), ctx.getContextPath());
        for (String candidate : candidates) {
            // 用 selectList 取首条而不是 selectOne：interface_path 上没有唯一约束，
            // 万一存在重复行，selectOne 会抛 TooManyResultsException（同类坑见 AppCredentialServiceImpl）。
            List<ApiInterface> hits = interfaceMapper.selectList(
                    new QueryWrapper<ApiInterface>()
                            .eq("interface_path", candidate)
                            .eq("request_method", ctx.getMethod())
                            .eq("status", 1)
                            .orderByAsc("id"));
            if (hits != null && !hits.isEmpty()) {
                apiInterface = hits.get(0);
                if (hits.size() > 1) {
                    log.warn("接口路径存在重复注册，取 id 最小的一条: path={}, hits={}", candidate, hits.size());
                }
                break;
            }
        }

        if (apiInterface == null) {
            throw GatewayException.notFound("接口不存在或未启用: " + ctx.getPath());
        }

        // 写入接口信息，供解密/转发/日志环节使用
        ctx.setInterfaceId(apiInterface.getId());
        ctx.setInterfacePath(apiInterface.getInterfacePath());
        ctx.setBackendUrl(apiInterface.getBackendUrl());
        ctx.setTimeoutMs(apiInterface.getTimeoutMs());
        // T13：解析生效环境配置并改写转发目标（接口级覆盖 > 分组继承 > 接口默认后端地址）
        applyEffectiveEnvConfig(ctx, apiInterface);

        // T04-A：优先按 app_api_grant 校验（状态机 + 有效期 + 环境）
        boolean granted = checkGrant(ctx, apiInterface.getId());

        // 兼容快照：grant 表无记录时回退到 app_api_permission（存量兼容）
        if (!granted) {
            AppApiPermission permission = permissionMapper.selectOne(
                    new QueryWrapper<AppApiPermission>()
                            .eq("app_id", ctx.getAppId())
                            .eq("interface_id", apiInterface.getId())
                            .eq("status", 1)
            );
            granted = permission != null;
        }

        if (!granted) {
            // 记录权限越界（频繁越界将触发安全告警/自动封禁）
            securityDetectionService.recordPermissionBreach(
                    ctx.getAppId(), ctx.getAppName(), ctx.getClientIp(), apiInterface.getId(), ctx.getPath());
            throw GatewayException.forbidden("无权调用此接口");
        }

        log.debug("Permission check passed: appId={}, interfaceId={}", ctx.getAppId(), apiInterface.getId());
    }

    /**
     * 按 app_api_grant 校验授权是否有效：
     * status=1 且 validFrom &lt;= 今天 &lt;= validTo 且 env 匹配（env 来自 ctx.getEnvCode()）。
     *
     * @param ctx         网关上下文
     * @param interfaceId 已匹配接口 ID
     * @return true=存在有效授权
     */
    private boolean checkGrant(GatewayContext ctx, Long interfaceId) {
        String envCode = ctx.getEnvCode();
        if (envCode == null || envCode.isEmpty()) {
            envCode = DEFAULT_ENV;
        }
        List<AppApiGrant> grants = appApiGrantMapper.selectList(
                new QueryWrapper<AppApiGrant>()
                        .eq("app_id", ctx.getAppId())
                        .eq("api_id", interfaceId)
                        .eq("env_code", envCode)
                        .eq("status", 1)
        );
        if (grants == null || grants.isEmpty()) {
            return false;
        }
        LocalDate today = LocalDate.now();
        for (AppApiGrant g : grants) {
            if (isValidNow(g, today)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断授权在指定日期是否有效：validFrom 不晚于 today 且 validTo 不早于 today（允许为空）。
     */
    private boolean isValidNow(AppApiGrant g, LocalDate today) {
        LocalDate from = g.getValidFrom();
        LocalDate to = g.getValidTo();
        if (from != null && from.isAfter(today)) {
            return false;
        }
        if (to != null && to.isBefore(today)) {
            return false;
        }
        return true;
    }
}
