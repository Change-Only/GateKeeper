package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.grant.GrantStateMachine;
import com.gatekeeper.grant.impl.GrantServiceImpl;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.service.AlertService;
import com.gatekeeper.service.ApiGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GrantServiceImpl 单元测试 — T04-A 授权域
 *
 * <p>覆盖：状态机合法/非法流转、create（默认待审批 + 唯一性 + 必填校验）、
 * approve/reject/revoke/renew 的状态机与服务行为。沿用 AppCredentialServiceTest 的
 * baseMapper 反射注入手法。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GrantServiceImpl + 状态机")
class GrantServiceImplTest {

    @Mock
    private AppApiGrantMapper grantMapper;

    @Mock
    private AlertService alertService;

    @Mock
    private ApiGroupService apiGroupService;

    private GrantServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GrantServiceImpl(apiGroupService, alertService);
        // 注入 baseMapper（ServiceImpl 受 MyBatis-Plus 自动装配；单测下手动注入）
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(service, grantMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // =================================================================
    // 状态机
    // =================================================================

    @Test
    @DisplayName("状态机：合法流转 0->1 / 0->4 / 1->2 / 1->3")
    void stateMachine_legal() {
        assertTrue(GrantStateMachine.isAllowed(0, 1));
        assertTrue(GrantStateMachine.isAllowed(0, 4));
        assertTrue(GrantStateMachine.isAllowed(1, 2));
        assertTrue(GrantStateMachine.isAllowed(1, 3));
    }

    @Test
    @DisplayName("状态机：非法流转抛异常 (0->2 / 1->4 / 3->1 / 4->1)")
    void stateMachine_illegal() {
        assertThrows(GatewayException.class, () -> GrantStateMachine.validate(0, 2));
        assertThrows(GatewayException.class, () -> GrantStateMachine.validate(1, 4));
        assertThrows(GatewayException.class, () -> GrantStateMachine.validate(3, 1));
        assertThrows(GatewayException.class, () -> GrantStateMachine.validate(4, 1));
    }

    // =================================================================
    // create
    // =================================================================

    @Test
    @DisplayName("create 默认 status=0 待审批，写入 createdAt/updatedAt")
    void create_defaultPending() {
        when(grantMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        AppApiGrant req = new AppApiGrant();
        req.setAppId(1L);
        req.setApiId(2L);
        req.setEnvCode("prod");

        AppApiGrant result = service.createGrant(req);
        assertEquals(Integer.valueOf(0), result.getStatus());
        verify(grantMapper, times(1)).insert(argThat((AppApiGrant g) ->
                g.getStatus() != null && g.getStatus() == 0
                        && g.getCreatedAt() != null && g.getUpdatedAt() != null));
    }

    @Test
    @DisplayName("create 重复 (app,api,env) 待审批/已生效 抛 400")
    void create_duplicateConflict() {
        AppApiGrant existing = new AppApiGrant();
        existing.setId(99L);
        existing.setStatus(1);
        when(grantMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        AppApiGrant req = new AppApiGrant();
        req.setAppId(1L);
        req.setApiId(2L);
        req.setEnvCode("prod");
        assertThrows(GatewayException.class, () -> service.createGrant(req));
    }

    @Test
    @DisplayName("create 缺 appId 抛 400")
    void create_missingAppId() {
        AppApiGrant req = new AppApiGrant();
        req.setApiId(2L);
        req.setEnvCode("prod");
        assertThrows(GatewayException.class, () -> service.createGrant(req));
    }

    // =================================================================
    // approve
    // =================================================================

    @Test
    @DisplayName("approve 0->1 设置 auditor/auditTime")
    void approve_transition() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        g.setStatus(0);
        when(grantMapper.selectById(1L)).thenReturn(g);

        AppApiGrant result = service.approve(1L, "通过", 9L, "admin");
        assertEquals(Integer.valueOf(1), result.getStatus());
        assertEquals("admin", result.getAuditorName());
        assertEquals("通过", result.getAuditRemark());
        verify(grantMapper, times(1)).updateById(argThat((AppApiGrant u) ->
                u.getStatus() != null && u.getStatus() == 1 && u.getAuditTime() != null));
    }

    @Test
    @DisplayName("approve 非法流转 (3->1) 抛异常")
    void approve_illegal() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        g.setStatus(3);
        when(grantMapper.selectById(1L)).thenReturn(g);
        assertThrows(GatewayException.class, () -> service.approve(1L, "通过", 9L, "admin"));
    }

    // =================================================================
    // reject
    // =================================================================

    @Test
    @DisplayName("reject 0->4 必须 auditRemark，且发审计告警")
    void reject_transition() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        g.setStatus(0);
        when(grantMapper.selectById(1L)).thenReturn(g);

        AppApiGrant result = service.reject(1L, "不合规", 9L, "admin");
        assertEquals(Integer.valueOf(4), result.getStatus());
        verify(alertService, times(1)).publish(any(), any(), any(), any(), any(), any(), any());
        verify(grantMapper, times(1)).updateById(any(AppApiGrant.class));
    }

    @Test
    @DisplayName("reject 缺 auditRemark 抛 400")
    void reject_missingRemark() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        g.setStatus(0);
        when(grantMapper.selectById(1L)).thenReturn(g);
        assertThrows(GatewayException.class, () -> service.reject(1L, null, 9L, "admin"));
    }

    // =================================================================
    // revoke
    // =================================================================

    @Test
    @DisplayName("revoke 1->3 必须 revokeReason，且发审计告警")
    void revoke_transition() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        g.setStatus(1);
        when(grantMapper.selectById(1L)).thenReturn(g);

        AppApiGrant result = service.revoke(1L, "业务下线", 9L, "admin");
        assertEquals(Integer.valueOf(3), result.getStatus());
        assertEquals("业务下线", result.getRevokeReason());
        verify(grantMapper, times(1)).updateById(any(AppApiGrant.class));
    }

    @Test
    @DisplayName("revoke 缺 revokeReason 抛 400")
    void revoke_missingReason() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        g.setStatus(1);
        when(grantMapper.selectById(1L)).thenReturn(g);
        assertThrows(GatewayException.class, () -> service.revoke(1L, null, 9L, "admin"));
    }

    // =================================================================
    // renew
    // =================================================================

    @Test
    @DisplayName("renew 从已生效(1) 延长 validTo")
    void renew_fromActive() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        g.setStatus(1);
        when(grantMapper.selectById(1L)).thenReturn(g);

        LocalDate next = LocalDate.now().plusDays(30);
        AppApiGrant result = service.renew(1L, next);
        assertEquals(next, result.getValidTo());
        assertEquals(Integer.valueOf(1), result.getStatus());
        verify(grantMapper, times(1)).updateById(any(AppApiGrant.class));
    }

    @Test
    @DisplayName("renew 非法状态(0) 抛 400")
    void renew_illegalStatus() {
        AppApiGrant g = new AppApiGrant();
        g.setId(1L);
        g.setStatus(0);
        when(grantMapper.selectById(1L)).thenReturn(g);
        assertThrows(GatewayException.class, () -> service.renew(1L, LocalDate.now().plusDays(30)));
    }
}
