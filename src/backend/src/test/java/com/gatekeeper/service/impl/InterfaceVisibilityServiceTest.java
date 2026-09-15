package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gatekeeper.dto.InterfaceVisibilityVo;
import com.gatekeeper.entity.SysInterfaceVisibility;
import com.gatekeeper.entity.SysRole;
import com.gatekeeper.entity.SysUser;
import com.gatekeeper.entity.SysUserRole;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.SysInterfaceVisibilityMapper;
import com.gatekeeper.mapper.SysRoleMapper;
import com.gatekeeper.mapper.SysUserMapper;
import com.gatekeeper.mapper.SysUserRoleMapper;
import com.gatekeeper.security.InterfaceViewer;
import com.gatekeeper.service.SysInterfaceCryptoConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InterfaceVisibilityServiceImpl} 单测 — T17。
 *
 * <p>本类只关心「谁能看到接口明文」这一件事。判定顺序见
 * {@link InterfaceViewer#canSee(Long)}：开关 → 超管 → owner → 白名单空 → 命中。</p>
 *
 * <p>重点覆盖两条<b>最容易写错</b>的语义：</p>
 * <ul>
 *   <li><b>白名单为空 ≠ 全员可见</b>。这与 {@code sys_ip_whitelist}（网络层准入，
 *       空表 = 不限制）<b>方向相反</b>：保护类闸门空表时必须收紧，只认超管与 owner。</li>
 *   <li><b>fail-safe 不是 fail-open</b>。白名单/角色查询异常时一律「不可见」，
 *       绝不能因为一次配置面故障就把明文放给所有人。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InterfaceVisibilityService 可见性白名单 T17")
class InterfaceVisibilityServiceTest {

    @Mock
    private SysInterfaceVisibilityMapper mapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysInterfaceCryptoConfigService configService;

    private InterfaceVisibilityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InterfaceVisibilityServiceImpl(
                mapper, userRoleMapper, roleMapper, userMapper, configService);
        RequestContextHolder.resetRequestAttributes();
    }

    // ---------------------------------------------------------------------
    // 构造替身
    // ---------------------------------------------------------------------

    private static SysUserRole link(Long userId, Long roleId) {
        SysUserRole l = new SysUserRole();
        l.setUserId(userId);
        l.setRoleId(roleId);
        return l;
    }

    private static SysRole role(Long id, String code) {
        SysRole r = new SysRole();
        r.setId(id);
        r.setRoleCode(code);
        r.setRoleName(code);
        r.setStatus(1);
        return r;
    }

    private static SysInterfaceVisibility vis(String type, Long subjectId, Integer status) {
        SysInterfaceVisibility v = new SysInterfaceVisibility();
        v.setId(subjectId == null ? 1L : subjectId);
        v.setSubjectType(type);
        v.setSubjectId(subjectId);
        v.setStatus(status);
        return v;
    }

    /** 常规桩：uid=uid 的用户拥有 roleId 一个角色（角色码 code） */
    private void givenUserWithRole(Long uid, Long roleId, String roleCode) {
        when(userRoleMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(link(uid, roleId)));
        if (roleId != null) {
            when(roleMapper.selectBatchIds(anyCollection()))
                    .thenReturn(Collections.singletonList(role(roleId, roleCode)));
        }
    }

    // =====================================================================
    // 开关
    // =====================================================================

    @Test
    @DisplayName("开关关闭 ⇒ unprotected：任何 owner 都可见，且零次 DB 查询")
    void switchOff_unprotectedAndZeroQueries() {
        when(configService.isEnabled()).thenReturn(false);

        InterfaceViewer v = service.resolveViewer(7L);

        assertFalse(v.isProtectionEnabled());
        assertTrue(v.canSee(null));
        assertTrue(v.canSee(999L));
        assertFalse(v.mustMask(999L));
        verifyNoInteractions(mapper, userRoleMapper, roleMapper, userMapper);
    }

    // =====================================================================
    // 超管兜底
    // =====================================================================

    @Test
    @DisplayName("SUPER_ADMIN 兜底放行：白名单非空但未命中，仍可见（防死锁）")
    void superAdmin_seesEvenWithoutWhitelistHit() {
        when(configService.isEnabled()).thenReturn(true);
        givenUserWithRole(1L, 10L, "SUPER_ADMIN");
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(vis("USER", 555L, 1)));

        InterfaceViewer v = service.resolveViewer(1L);

        assertTrue(v.isSuperAdmin());
        assertFalse(v.isWhitelistEmpty());
        assertTrue(v.canSee(999L), "超管必须能看所有人");
        assertTrue(v.canSee(null));
    }

    @Test
    @DisplayName("非超管角色（OPERATOR）不获得兜底")
    void nonSuperAdmin_noFallback() {
        when(configService.isEnabled()).thenReturn(true);
        givenUserWithRole(2L, 3L, "OPERATOR");
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(vis("USER", 555L, 1)));

        InterfaceViewer v = service.resolveViewer(2L);

        assertFalse(v.isSuperAdmin());
        assertFalse(v.canSee(null), "没命中白名单就不该可见");
    }

    // =====================================================================
    // 🔴 白名单为空的方向（与 ip_whitelist 相反）
    // =====================================================================

    @Test
    @DisplayName("🔴 白名单为空 ⇒ 只认超管与 owner（空表必须收紧，不是『不限制』）")
    void whitelistEmpty_onlySuperAdminAndOwner() {
        when(configService.isEnabled()).thenReturn(true);
        givenUserWithRole(5L, 3L, "OPERATOR");
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        InterfaceViewer v = service.resolveViewer(5L);

        assertTrue(v.isWhitelistEmpty());
        assertFalse(v.isSuperAdmin());
        assertTrue(v.canSee(5L), "owner 本人始终可见");
        assertFalse(v.canSee(6L), "非 owner 且白名单为空 ⇒ 掩码");
        assertFalse(v.canSee(null));
    }

    @Test
    @DisplayName("白名单为空且 mapper 返回 null 也按『空』处理（不 NPE、不放行）")
    void whitelistNullTreatedAsEmpty() {
        when(configService.isEnabled()).thenReturn(true);
        when(userRoleMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(null);

        InterfaceViewer v = service.resolveViewer(5L);

        assertTrue(v.isWhitelistEmpty());
        assertFalse(v.canSee(6L));
    }

    // =====================================================================
    // 白名单命中
    // =====================================================================

    @Test
    @DisplayName("命中 (USER, uid) ⇒ 可见")
    void userHit() {
        when(configService.isEnabled()).thenReturn(true);
        when(userRoleMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(vis("USER", 5L, 1)));

        InterfaceViewer v = service.resolveViewer(5L);

        assertTrue(v.isUserHit());
        assertFalse(v.isWhitelistEmpty());
        assertTrue(v.canSee(null));
        assertTrue(v.canSee(999L), "白名单内用户可看任意接口明文");
    }

    @Test
    @DisplayName("命中 (ROLE, 当前用户角色) ⇒ 可见")
    void roleHit() {
        when(configService.isEnabled()).thenReturn(true);
        givenUserWithRole(5L, 3L, "OPERATOR");
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(vis("ROLE", 3L, 1)));

        InterfaceViewer v = service.resolveViewer(5L);

        assertTrue(v.isRoleHit());
        assertTrue(v.canSee(null));
    }

    @Test
    @DisplayName("白名单有内容但都没命中 ⇒ 不可见（非误判为『空表放行』）")
    void whitelistNonEmptyButNoHit() {
        when(configService.isEnabled()).thenReturn(true);
        givenUserWithRole(5L, 3L, "OPERATOR");
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Arrays.asList(
                vis("USER", 999L, 1),
                vis("ROLE", 77L, 1)));

        InterfaceViewer v = service.resolveViewer(5L);

        assertFalse(v.isWhitelistEmpty());
        assertFalse(v.isUserHit());
        assertFalse(v.isRoleHit());
        assertFalse(v.canSee(null));
    }

    @Test
    @DisplayName("未登录（uid=null）不成 owner，也不命中 USER 白名单")
    void anonymousCannotSee() {
        when(configService.isEnabled()).thenReturn(true);
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(vis("USER", 5L, 1)));

        InterfaceViewer v = service.resolveViewer(null);

        assertNull(v.getUid());
        assertFalse(v.canSee(5L));
        assertFalse(v.canSee(null));
        // uid 为 null 时不该去查角色
        verify(userRoleMapper, never()).selectList(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("白名单主体 subjectId 为 null 的行被跳过（脏数据不放大权限）")
    void nullSubjectIdSkipped() {
        when(configService.isEnabled()).thenReturn(true);
        when(userRoleMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(vis("USER", null, 1)));

        InterfaceViewer v = service.resolveViewer(null);

        assertFalse(v.isUserHit());
        assertFalse(v.canSee(null));
    }

    @Test
    @DisplayName("🔴 只查 status=1 的白名单（停用行不得参与判定）")
    void onlyEnabledRowsQueried() {
        when(configService.isEnabled()).thenReturn(true);
        when(userRoleMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        ArgumentCaptor<QueryWrapper<SysInterfaceVisibility>> cap =
                ArgumentCaptor.forClass(QueryWrapper.class);
        when(mapper.selectList(cap.capture())).thenReturn(Collections.emptyList());

        service.resolveViewer(1L);

        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "查询必须带 status 条件，实际：" + sql);
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue(1),
                "status 条件值必须是 1（启用）");
    }

    // =====================================================================
    // 🔴 fail-safe
    // =====================================================================

    @Test
    @DisplayName("🔴 fail-safe：角色查询异常 ⇒ 一律掩码，绝不 fail-open 放明文")
    void roleQueryException_failSafeMaskAll() {
        when(configService.isEnabled()).thenReturn(true);
        when(userRoleMapper.selectList(any(QueryWrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        InterfaceViewer v = service.resolveViewer(5L);

        assertTrue(v.isProtectionEnabled(), "仍然处于保护态");
        assertFalse(v.isSuperAdmin());
        assertTrue(v.isWhitelistEmpty());
        assertFalse(v.canSee(null), "异常时不能因为『白名单空』放行");
        assertFalse(v.canSee(6L));
        assertFalse(v.canSee(5L), "fail-safe 下连 owner 也不放行（身份未确认）");
    }

    @Test
    @DisplayName("🔴 fail-safe：白名单查询异常 ⇒ 同样一律掩码")
    void whitelistQueryException_failSafeMaskAll() {
        when(configService.isEnabled()).thenReturn(true);
        when(userRoleMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenThrow(new RuntimeException("db down"));

        InterfaceViewer v = service.resolveViewer(5L);

        assertTrue(v.isProtectionEnabled());
        assertTrue(v.isWhitelistEmpty());
        assertFalse(v.canSee(null));
        assertFalse(v.canSee(5L));
    }

    // =====================================================================
    // currentUid
    // =====================================================================

    @Test
    @DisplayName("currentUid 无请求上下文时返回 null（不抛异常）")
    void currentUid_noRequestContext() {
        assertNull(service.currentUid());
    }

    @Test
    @DisplayName("currentUid 从 X-USER-ID 请求属性解析（支持 Number 与 String 两种写入）")
    void currentUid_fromRequestAttribute() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("X-USER-ID", 42L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        try {
            assertEquals(42L, service.currentUid());

            req.setAttribute("X-USER-ID", "43");
            assertEquals(43L, service.currentUid());

            req.setAttribute("X-USER-ID", "not-a-number");
            assertNull(service.currentUid(), "非法值按未登录处理");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    // =====================================================================
    // 白名单 CRUD
    // =====================================================================

    @Test
    @DisplayName("list 批量补主体名，孤儿行标记 subjectMissing 但不删")
    void list_enrichesLabelsAndMarksOrphans() {
        SysInterfaceVisibility userRow = vis("USER", 5L, 1);
        userRow.setId(1L);
        SysInterfaceVisibility roleRow = vis("ROLE", 9L, 1);
        roleRow.setId(2L);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Arrays.asList(userRow, roleRow));

        SysUser u = new SysUser();
        u.setId(5L);
        u.setUsername("alice");
        u.setRealName("Alice");
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(u));
        when(roleMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());

        List<InterfaceVisibilityVo> out = service.list();

        assertEquals(2, out.size());
        assertEquals("Alice", out.get(0).getSubjectLabel());
        assertEquals("alice", out.get(0).getSubjectCode());
        assertFalse(out.get(0).getSubjectMissing());

        assertTrue(out.get(1).getSubjectMissing(), "角色已不存在 ⇒ 标记为孤儿");
        assertNull(out.get(1).getSubjectLabel());
        // 刻意不自动清理：只标记，交给运维决定
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("list 空表返回空列表且不查主体表")
    void list_empty() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        assertTrue(service.list().isEmpty());
        verify(userMapper, never()).selectBatchIds(anyCollection());
    }

    @Test
    @DisplayName("add 主体类型非法 ⇒ 400")
    void add_invalidType() {
        SysInterfaceVisibility e = vis("GROUP", 1L, 1);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.add(e));
        assertEquals(400, ex.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("add 主体不存在 ⇒ 400（不留『永远匹配不上』的孤儿行）")
    void add_subjectMissing() {
        when(userMapper.selectById(99L)).thenReturn(null);
        SysInterfaceVisibility e = vis("USER", 99L, 1);

        GatewayException ex = assertThrows(GatewayException.class, () -> service.add(e));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("99"));
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("add 主体重复 ⇒ 400（提前给出可读错误，避免 DuplicateKey 变 500）")
    void add_duplicated() {
        SysUser u = new SysUser();
        u.setId(5L);
        u.setUsername("alice");
        when(userMapper.selectById(5L)).thenReturn(u);
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(vis("USER", 5L, 1)));

        SysInterfaceVisibility e = vis("USER", 5L, 1);
        GatewayException ex = assertThrows(GatewayException.class, () -> service.add(e));
        assertEquals(400, ex.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("add 正常路径：类型归一化为大写、缺省 status=1、忽略入参 id")
    void add_happyPath_normalizes() {
        SysUser u = new SysUser();
        u.setId(5L);
        u.setUsername("alice");
        when(userMapper.selectById(5L)).thenReturn(u);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        SysInterfaceVisibility e = new SysInterfaceVisibility();
        e.setId(123L);                 // 应被忽略
        e.setSubjectType(" user ");    // 应归一化为 USER
        e.setSubjectId(5L);
        e.setRemark("  张三需要看接口  ");
        e.setStatus(null);             // 应缺省为 1

        service.add(e);

        ArgumentCaptor<SysInterfaceVisibility> cap =
                ArgumentCaptor.forClass(SysInterfaceVisibility.class);
        verify(mapper).insert(cap.capture());
        SysInterfaceVisibility saved = cap.getValue();
        assertNull(saved.getId(), "入参 id 必须清空，由 DB 生成");
        assertEquals("USER", saved.getSubjectType());
        assertEquals(Integer.valueOf(1), saved.getStatus());
        assertEquals("张三需要看接口", saved.getRemark());
        assertEquals(Long.valueOf(5L), saved.getSubjectId());
    }

    @Test
    @DisplayName("update 不存在的 id ⇒ 404；delete 不存在 ⇒ 404")
    void updateDelete_notFound() {
        when(mapper.selectById(404L)).thenReturn(null);

        GatewayException ex1 = assertThrows(GatewayException.class,
                () -> service.update(404L, vis("USER", 5L, 1)));
        assertEquals(404, ex1.getCode());

        GatewayException ex2 = assertThrows(GatewayException.class, () -> service.delete(404L));
        assertEquals(404, ex2.getCode());

        verify(mapper, never()).update(any(), any());
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("update 用 UpdateWrapper 显式 set（允许把 remark 清空为 null）")
    void update_usesExplicitSet() {
        SysInterfaceVisibility existing = vis("USER", 5L, 1);
        existing.setId(1L);
        when(mapper.selectById(1L)).thenReturn(existing);
        SysUser u = new SysUser();
        u.setId(5L);
        when(userMapper.selectById(5L)).thenReturn(u);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        SysInterfaceVisibility e = vis("USER", 5L, 0);
        e.setRemark(null); // 显式清空

        service.update(1L, e);

        ArgumentCaptor<UpdateWrapper<SysInterfaceVisibility>> cap =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), cap.capture());
        String setClause = cap.getValue().getSqlSet();
        assertTrue(setClause.contains("remark"),
                "必须显式 set remark，否则 NOT_NULL 策略会跳过 null 导致清不掉：" + setClause);
        assertTrue(setClause.contains("status"), setClause);
    }

    @Test
    @DisplayName("subjectOptions 返回 users 与 roles 两组候选")
    void subjectOptions() {
        SysUser u = new SysUser();
        u.setId(5L);
        u.setUsername("alice");
        u.setRealName("Alice");
        when(userMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(u));
        when(roleMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(role(3L, "OPERATOR")));

        Map<String, Object> out = service.subjectOptions();

        assertEquals(2, out.size());
        List<?> users = (List<?>) out.get("users");
        List<?> roles = (List<?>) out.get("roles");
        assertEquals(1, users.size());
        assertEquals(1, roles.size());
        assertEquals("Alice", ((Map<?, ?>) users.get(0)).get("label"));
        assertEquals("alice", ((Map<?, ?>) users.get(0)).get("code"));
        assertEquals("OPERATOR", ((Map<?, ?>) roles.get(0)).get("code"));
    }

    @Test
    @DisplayName("resolveViewer() 无参重载走 currentUid（无上下文即匿名）")
    void resolveViewer_noArg_usesCurrentUid() {
        when(configService.isEnabled()).thenReturn(true);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        InterfaceViewer v = service.resolveViewer();

        assertNull(v.getUid());
        assertTrue(v.isProtectionEnabled());
    }

    @Test
    @DisplayName("同一 uid 多角色时，任一角色命中即 roleHit")
    void roleHit_multiRoles() {
        when(configService.isEnabled()).thenReturn(true);
        List<SysUserRole> links = new ArrayList<>();
        links.add(link(5L, 3L));
        links.add(link(5L, 8L));
        when(userRoleMapper.selectList(any(QueryWrapper.class))).thenReturn(links);
        when(roleMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(
                role(3L, "OPERATOR"), role(8L, "BIZ_ADMIN")));
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(vis("ROLE", 8L, 1)));

        InterfaceViewer v = service.resolveViewer(5L);

        assertTrue(v.isRoleHit());
        assertTrue(v.canSee(null));
        verify(roleMapper, times(1)).selectBatchIds(anyCollection());
    }
}
