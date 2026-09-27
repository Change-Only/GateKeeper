package com.gatekeeper.grant.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.grant.GrantService;
import com.gatekeeper.grant.GrantStateMachine;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.service.AlertService;
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

    /** 审计告警服务：审批/驳回/撤销动作落库告警（fail-safe，失败不影响主流程） */
    private final AlertService alertService;

    private static final String DEFAULT_ENV = "prod";

    /**
     * 单次延期的最大天数（P0-2 业务约束）。
     *
     * <p>延期不再允许由请求体任意指定「超长/无上限」的到期日；超过该上限一律 400。
     * 「累计延期上限」需记录原始到期日，属后续独立课题（本轮未实现，已知限制）。</p>
     */
    private static final long MAX_RENEW_DAYS = 365L;

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
        LocalDate today = LocalDate.now();
        LocalDate target = validTo != null ? validTo : today.plusDays(30);

        // P0-2 纵深防御：不依赖单一权限点（Controller 层的 @RequirePerm 是第一道闸），
        // 服务层再对「新到期日」加业务约束，防止「有延期权限的人」把有效期改坏。
        // 1) 到期日不得早于今天（拒绝过去时间）
        if (target.isBefore(today)) {
            throw GatewayException.badRequest("延期到期日不能早于今天：" + target);
        }
        // 2) 不得早于当前有效期（防「缩短后伪造」）
        LocalDate current = grant.getValidTo();
        if (current != null && target.isBefore(current)) {
            throw GatewayException.badRequest("延期到期日不能早于当前有效期：" + current);
        }
        // 3) 单次延期上限（防止一次把授权延成事实永不过期）
        LocalDate maxAllowed = today.plusDays(MAX_RENEW_DAYS);
        if (target.isAfter(maxAllowed)) {
            throw GatewayException.badRequest("单次延期不得超过 " + MAX_RENEW_DAYS
                    + " 天（新到期日 " + target + " 超出上限 " + maxAllowed + "）");
        }

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
}
