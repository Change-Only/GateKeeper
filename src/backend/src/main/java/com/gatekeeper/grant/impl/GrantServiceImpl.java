package com.gatekeeper.grant.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.grant.GrantService;
import com.gatekeeper.grant.GrantStateMachine;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.service.AlertService;
import com.gatekeeper.service.ApiGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 授权域服务实现 — T04-A 授权（审批流）
 *
 * <p>围绕 app_api_grant 实现带审批/有效期/环境的授权全生命周期，
 * 所有状态流转经 {@link GrantStateMachine} 校验，非法流转抛 GatewayException.badRequest。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrantServiceImpl extends ServiceImpl<AppApiGrantMapper, AppApiGrant> implements GrantService {

    private final ApiGroupService apiGroupService;

    /** 审计告警服务：审批/驳回/撤销动作落库告警（fail-safe，失败不影响主流程） */
    private final AlertService alertService;

    private static final String DEFAULT_ENV = "prod";

    // =====================================================================
    // 查询
    // =====================================================================

    @Override
    public List<AppApiGrant> listGrants(Long appId, Long apiId, String envCode, Integer status) {
        QueryWrapper<AppApiGrant> wrapper = new QueryWrapper<>();
        if (appId != null) {
            wrapper.eq("app_id", appId);
        }
        if (apiId != null) {
            wrapper.eq("api_id", apiId);
        }
        if (StringUtils.hasText(envCode)) {
            wrapper.eq("env_code", envCode);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("created_at");
        return baseMapper.selectList(wrapper);
    }

    @Override
    public AppApiGrant getGrant(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("授权ID不能为空");
        }
        AppApiGrant grant = baseMapper.selectById(id);
        if (grant == null) {
            throw GatewayException.notFound("授权不存在: id=" + id);
        }
        return grant;
    }

    // =====================================================================
    // 创建（待审批）
    // =====================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppApiGrant createGrant(AppApiGrant grant) {
        if (grant == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (grant.getAppId() == null) {
            throw GatewayException.badRequest("应用ID不能为空");
        }
        if (grant.getApiId() == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        String envCode = StringUtils.hasText(grant.getEnvCode()) ? grant.getEnvCode() : DEFAULT_ENV;
        grant.setEnvCode(envCode);

        // 唯一约束：同一 app+api+env 下不允许重复存在待审批/生效中的授权
        long dup = baseMapper.selectCount(new QueryWrapper<AppApiGrant>()
                .eq("app_id", grant.getAppId())
                .eq("api_id", grant.getApiId())
                .eq("env_code", envCode)
                .in("status", GrantStateMachine.PENDING, GrantStateMachine.ACTIVE));
        if (dup > 0) {
            throw GatewayException.badRequest("该应用在此环境下对该接口已存在待审批或生效中的授权");
        }

        grant.setId(null);
        grant.setStatus(GrantStateMachine.PENDING); // 默认待审批
        LocalDateTime now = LocalDateTime.now();
        grant.setCreatedAt(now);
        grant.setUpdatedAt(now);
        baseMapper.insert(grant);
        log.info("Grant created (pending): id={}, appId={}, apiId={}, envCode={}",
                grant.getId(), grant.getAppId(), grant.getApiId(), envCode);
        return grant;
    }

    // =====================================================================
    // 审批流：通过 / 驳回 / 撤销 / 延期
    // =====================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppApiGrant approve(Long id, String auditRemark, Long auditorId, String auditorName) {
        AppApiGrant grant = getGrant(id);
        GrantStateMachine.validate(grant.getStatus(), GrantStateMachine.ACTIVE);
        grant.setStatus(GrantStateMachine.ACTIVE);
        grant.setAuditorId(auditorId);
        grant.setAuditorName(auditorName);
        grant.setAuditRemark(auditRemark);
        grant.setAuditTime(LocalDateTime.now());
        grant.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(grant);
        log.info("Grant approved: id={}, auditor={}", id, auditorName);
        return grant;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppApiGrant reject(Long id, String auditRemark, Long auditorId, String auditorName) {
        if (!StringUtils.hasText(auditRemark)) {
            throw GatewayException.badRequest("驳回必须填写审批意见");
        }
        AppApiGrant grant = getGrant(id);
        GrantStateMachine.validate(grant.getStatus(), GrantStateMachine.REJECTED);
        grant.setStatus(GrantStateMachine.REJECTED);
        grant.setAuditorId(auditorId);
        grant.setAuditorName(auditorName);
        grant.setAuditRemark(auditRemark);
        grant.setAuditTime(LocalDateTime.now());
        grant.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(grant);
        log.info("Grant rejected: id={}", id);
        // 审计告警（fail-safe：发布失败不影响主流程）
        try {
            alertService.publish("WARNING", "SECURITY", "授权驳回",
                    "授权ID=" + id + " 被驳回，原因: " + auditRemark,
                    grant.getAppId(), null, null);
        } catch (Exception ignored) {
            log.warn("Failed to publish reject alert (non-critical): id={}", id);
        }
        return grant;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppApiGrant revoke(Long id, String revokeReason, Long auditorId, String auditorName) {
        if (!StringUtils.hasText(revokeReason)) {
            throw GatewayException.badRequest("撤销必须填写撤销原因");
        }
        AppApiGrant grant = getGrant(id);
        GrantStateMachine.validate(grant.getStatus(), GrantStateMachine.REVOKED);
        grant.setStatus(GrantStateMachine.REVOKED);
        grant.setAuditorId(auditorId);
        grant.setAuditorName(auditorName);
        grant.setRevokeReason(revokeReason);
        grant.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(grant);
        log.info("Grant revoked: id={}", id);
        return grant;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppApiGrant renew(Long id, LocalDate validTo) {
        AppApiGrant grant = getGrant(id);
        if (grant.getStatus() != GrantStateMachine.ACTIVE && grant.getStatus() != GrantStateMachine.EXPIRED) {
            throw GatewayException.badRequest("仅生效中或已过期的授权可延期");
        }
        LocalDate target = validTo != null ? validTo : LocalDate.now().plusDays(30);
        grant.setValidTo(target);
        grant.setStatus(GrantStateMachine.ACTIVE); // 延期后视为生效
        grant.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(grant);
        log.info("Grant renewed: id={}, validTo={}", id, target);
        return grant;
    }

    // =====================================================================
    // 兼容旧权限模型
    // =====================================================================

    @Override
    public List<AppApiGrant> listPending(String envCode) {
        QueryWrapper<AppApiGrant> wrapper = new QueryWrapper<>();
        wrapper.eq("status", GrantStateMachine.PENDING);
        if (StringUtils.hasText(envCode)) {
            wrapper.eq("env_code", envCode);
        }
        wrapper.orderByDesc("created_at");
        return baseMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchCreate(Long appId, List<Long> apiIds) {
        if (appId == null || apiIds == null || apiIds.isEmpty()) {
            return;
        }
        for (Long apiId : apiIds) {
            AppApiGrant grant = new AppApiGrant();
            grant.setAppId(appId);
            grant.setApiId(apiId);
            grant.setEnvCode(DEFAULT_ENV);
            createGrant(grant);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createByGroup(Long appId, Long groupId) {
        if (appId == null || groupId == null) {
            return;
        }
        List<ApiInterface> interfaces = apiGroupService.getInterfacesByGroup(groupId);
        if (interfaces == null || interfaces.isEmpty()) {
            return;
        }
        for (ApiInterface iface : interfaces) {
            AppApiGrant grant = new AppApiGrant();
            grant.setAppId(appId);
            grant.setApiId(iface.getId());
            grant.setEnvCode(DEFAULT_ENV);
            createGrant(grant);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeByAppAndApi(Long appId, Long apiId) {
        if (appId == null || apiId == null) {
            return;
        }
        AppApiGrant grant = baseMapper.selectOne(new QueryWrapper<AppApiGrant>()
                .eq("app_id", appId)
                .eq("api_id", apiId)
                .in("status", GrantStateMachine.PENDING, GrantStateMachine.ACTIVE)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
        if (grant != null) {
            revoke(grant.getId(), "通过权限管理接口撤销", null, null);
        }
    }
}
