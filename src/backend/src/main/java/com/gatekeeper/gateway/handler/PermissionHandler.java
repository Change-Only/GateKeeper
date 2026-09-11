package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.entity.AppApiPermission;
import com.gatekeeper.exception.GatewayException;
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
     * 查找接口并校验应用调用权限
     *
     * @param ctx 网关上下文（需已写入 appId；成功后写入 interfaceId/backendUrl/timeoutMs）
     */
    @Override
    public void handle(GatewayContext ctx) {
        // 根据路径 + 方法 + 启用状态精确匹配接口
        ApiInterface apiInterface = interfaceMapper.selectOne(
                new QueryWrapper<ApiInterface>()
                        .eq("interface_path", ctx.getPath())
                        .eq("request_method", ctx.getMethod())
                        .eq("status", 1)
        );

        if (apiInterface == null) {
            throw GatewayException.notFound("接口不存在或未启用: " + ctx.getPath());
        }

        // 写入接口信息，供解密/转发/日志环节使用
        ctx.setInterfaceId(apiInterface.getId());
        ctx.setInterfacePath(apiInterface.getInterfacePath());
        ctx.setBackendUrl(apiInterface.getBackendUrl());
        ctx.setTimeoutMs(apiInterface.getTimeoutMs());

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
