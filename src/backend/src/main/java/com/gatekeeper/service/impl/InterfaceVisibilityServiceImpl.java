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
import com.gatekeeper.service.InterfaceVisibilityService;
import com.gatekeeper.service.SysInterfaceCryptoConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 接口信息可见性服务实现 — T17
 *
 * <h3>判定顺序（与 {@link InterfaceViewer#canSee(Long)} 一致）</h3>
 * <ol>
 *   <li>保护开关关闭 ⇒ 全部可见（不掩码）；</li>
 *   <li>SUPER_ADMIN ⇒ 可见（break-glass，防死锁）；</li>
 *   <li>是该接口的 owner ⇒ 可见；</li>
 *   <li>白名单为空 ⇒ 不可见（保护类闸门的默认方向）；</li>
 *   <li>命中 (USER, uid) 或 (ROLE, 任一角色) ⇒ 可见；否则不可见。</li>
 * </ol>
 *
 * <h3>为什么一次请求只解析一次</h3>
 * <p>接口列表一页 N 行。若按行查白名单/角色，一次分页会产生 O(N) 次 DB 往返。
 * {@link #resolveViewer()} 把「与行无关」的部分一次性算完，
 * 每行只剩纯内存判断。</p>
 *
 * <h3>fail-safe：解析异常 ⇒ 一律掩码</h3>
 * <p>角色查询或白名单查询抛异常时，返回 {@link InterfaceViewer#failSafeMaskAll()}。
 * 这与 {@code SysAccessWhitelistHandler} 的 fail-open 方向<b>相反</b>，是刻意的：
 * 一个是「准入」闸门（怕误拦），一个是「保护」闸门（怕误放）。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterfaceVisibilityServiceImpl implements InterfaceVisibilityService {

    /** 兜底放行的角色编码 */
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private final SysInterfaceVisibilityMapper mapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserMapper userMapper;
    private final SysInterfaceCryptoConfigService configService;

    // =====================================================================
    // 可见性判定
    // =====================================================================

    @Override
    public InterfaceViewer resolveViewer() {
        return resolveViewer(currentUid());
    }

    @Override
    public InterfaceViewer resolveViewer(Long uid) {
        if (!configService.isEnabled()) {
            // 开关关闭：不加密也不掩码，白名单完全不参与（零查询，热路径最省）
            return InterfaceViewer.unprotected();
        }
        try {
            InterfaceViewer viewer = new InterfaceViewer();
            viewer.setProtectionEnabled(true);
            viewer.setUid(uid);
            viewer.setSuperAdmin(false);
            viewer.setWhitelistEmpty(true);
            viewer.setUserHit(false);
            viewer.setRoleHit(false);

            // 1) 当前用户的角色（同时拿到 roleId 与 roleCode，省掉"再查一次超管角色 id"）
            Set<Long> roleIds = new HashSet<>();
            if (uid != null) {
                List<SysUserRole> links = userRoleMapper.selectList(
                        new QueryWrapper<SysUserRole>().eq("user_id", uid));
                if (links != null) {
                    for (SysUserRole link : links) {
                        if (link.getRoleId() != null) {
                            roleIds.add(link.getRoleId());
                        }
                    }
                }
            }
            if (!roleIds.isEmpty()) {
                List<SysRole> roles = roleMapper.selectBatchIds(roleIds);
                if (roles != null) {
                    for (SysRole r : roles) {
                        if (ROLE_SUPER_ADMIN.equals(r.getRoleCode())) {
                            viewer.setSuperAdmin(true);
                            break;
                        }
                    }
                }
            }

            // 2) 白名单（只看 status=1）
            List<SysInterfaceVisibility> entries = mapper.selectList(
                    new QueryWrapper<SysInterfaceVisibility>().eq("status", SysInterfaceVisibility.STATUS_ENABLED));
            if (entries == null || entries.isEmpty()) {
                return viewer; // whitelistEmpty 保持 true ⇒ 只认超管与 owner
            }
            viewer.setWhitelistEmpty(false);
            for (SysInterfaceVisibility e : entries) {
                if (e.getSubjectId() == null) {
                    continue;
                }
                if (SysInterfaceVisibility.TYPE_USER.equals(e.getSubjectType())) {
                    if (uid != null && uid.equals(e.getSubjectId())) {
                        viewer.setUserHit(true);
                    }
                } else if (SysInterfaceVisibility.TYPE_ROLE.equals(e.getSubjectType())) {
                    if (roleIds.contains(e.getSubjectId())) {
                        viewer.setRoleHit(true);
                    }
                }
            }
            return viewer;
        } catch (Exception e) {
            // 保护类闸门：解析失败一律按"不可见"处理，绝不放明文出去
            log.warn("解析接口信息可见性失败，本次一律按掩码处理（fail-safe 保护优先）: {}", e.getMessage());
            return InterfaceViewer.failSafeMaskAll();
        }
    }

    @Override
    public Long currentUid() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            Object v = attrs.getRequest().getAttribute("X-USER-ID");
            if (v == null) {
                return null;
            }
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
            return Long.valueOf(String.valueOf(v));
        } catch (Exception e) {
            log.debug("解析当前用户 id 失败，按未登录处理: {}", e.getMessage());
            return null;
        }
    }

    // =====================================================================
    // 白名单 CRUD
    // =====================================================================

    @Override
    public List<InterfaceVisibilityVo> list() {
        List<SysInterfaceVisibility> rows = mapper.selectList(
                new QueryWrapper<SysInterfaceVisibility>()
                        .orderByDesc("status")
                        .orderByAsc("subject_type")
                        .orderByAsc("subject_id"));
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, SysUser> users = loadUsers(rows);
        Map<Long, SysRole> roles = loadRoles(rows);
        List<InterfaceVisibilityVo> out = new ArrayList<>(rows.size());
        for (SysInterfaceVisibility e : rows) {
            InterfaceVisibilityVo vo = new InterfaceVisibilityVo();
            vo.setId(e.getId());
            vo.setSubjectType(e.getSubjectType());
            vo.setSubjectId(e.getSubjectId());
            vo.setRemark(e.getRemark());
            vo.setStatus(e.getStatus());
            vo.setCreatedAt(e.getCreatedAt());
            vo.setUpdatedAt(e.getUpdatedAt());
            if (SysInterfaceVisibility.TYPE_USER.equals(e.getSubjectType())) {
                SysUser u = e.getSubjectId() == null ? null : users.get(e.getSubjectId());
                vo.setSubjectMissing(u == null);
                vo.setSubjectLabel(u == null ? null : displayName(u));
                vo.setSubjectCode(u == null ? null : u.getUsername());
            } else {
                SysRole r = e.getSubjectId() == null ? null : roles.get(e.getSubjectId());
                vo.setSubjectMissing(r == null);
                vo.setSubjectLabel(r == null ? null : r.getRoleName());
                vo.setSubjectCode(r == null ? null : r.getRoleCode());
            }
            out.add(vo);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(SysInterfaceVisibility entry) {
        if (entry == null) {
            throw GatewayException.badRequest("白名单内容不能为空");
        }
        String type = normalizeType(entry.getSubjectType());
        assertSubjectExists(type, entry.getSubjectId());
        assertNotDuplicated(type, entry.getSubjectId(), null);

        entry.setId(null);
        entry.setSubjectType(type);
        entry.setStatus(entry.getStatus() == null
                ? SysInterfaceVisibility.STATUS_ENABLED : entry.getStatus());
        entry.setRemark(StringUtils.hasText(entry.getRemark()) ? entry.getRemark().trim() : null);
        entry.setCreatedAt(LocalDateTime.now());
        entry.setUpdatedAt(LocalDateTime.now());
        mapper.insert(entry);
        log.info("Interface visibility added: id={}, type={}, subjectId={}, status={}",
                entry.getId(), type, entry.getSubjectId(), entry.getStatus());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, SysInterfaceVisibility entry) {
        if (id == null) {
            throw GatewayException.badRequest("白名单ID不能为空");
        }
        SysInterfaceVisibility existing = mapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("接口信息可见性白名单不存在");
        }
        if (entry == null) {
            throw GatewayException.badRequest("白名单内容不能为空");
        }
        String type = normalizeType(entry.getSubjectType());
        assertSubjectExists(type, entry.getSubjectId());
        assertNotDuplicated(type, entry.getSubjectId(), id);

        int status = entry.getStatus() == null
                ? SysInterfaceVisibility.STATUS_ENABLED : entry.getStatus();
        String remark = StringUtils.hasText(entry.getRemark()) ? entry.getRemark().trim() : null;

        // 显式 set：允许把 remark 写回 null（updateById 的 NOT_NULL 策略做不到）
        mapper.update(null, new UpdateWrapper<SysInterfaceVisibility>()
                .eq("id", id)
                .set("subject_type", type)
                .set("subject_id", entry.getSubjectId())
                .set("remark", remark)
                .set("status", status)
                .set("updated_at", LocalDateTime.now()));
        log.info("Interface visibility updated: id={}, type={}, subjectId={}, status={}",
                id, type, entry.getSubjectId(), status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("白名单ID不能为空");
        }
        SysInterfaceVisibility existing = mapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("接口信息可见性白名单不存在");
        }
        mapper.deleteById(id);
        log.info("Interface visibility deleted: id={}, type={}, subjectId={}",
                id, existing.getSubjectType(), existing.getSubjectId());
    }

    @Override
    public Map<String, Object> subjectOptions() {
        Map<String, Object> out = new LinkedHashMap<>();

        List<SysUser> users = userMapper.selectList(
                new QueryWrapper<SysUser>().eq("status", 1).orderByAsc("id"));
        List<Map<String, Object>> userOpts = new ArrayList<>();
        if (users != null) {
            for (SysUser u : users) {
                userOpts.add(option(u.getId(), displayName(u), u.getUsername()));
            }
        }
        out.put("users", userOpts);

        List<SysRole> roles = roleMapper.selectList(
                new QueryWrapper<SysRole>().eq("status", 1).orderByAsc("id"));
        List<Map<String, Object>> roleOpts = new ArrayList<>();
        if (roles != null) {
            for (SysRole r : roles) {
                roleOpts.add(option(r.getId(), r.getRoleName(), r.getRoleCode()));
            }
        }
        out.put("roles", roleOpts);
        return out;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private static Map<String, Object> option(Long id, String label, String code) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("code", code);
        return m;
    }

    /** 展示名：优先真实姓名，缺失时退回登录账号 */
    private static String displayName(SysUser u) {
        return StringUtils.hasText(u.getRealName()) ? u.getRealName() : u.getUsername();
    }

    private String normalizeType(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw GatewayException.badRequest("主体类型不能为空（USER 或 ROLE）");
        }
        String t = raw.trim().toUpperCase();
        if (!SysInterfaceVisibility.TYPE_USER.equals(t) && !SysInterfaceVisibility.TYPE_ROLE.equals(t)) {
            throw GatewayException.badRequest("主体类型只能为 USER 或 ROLE，当前为：" + raw);
        }
        return t;
    }

    /**
     * 校验主体存在。
     *
     * <p>不校验就等于允许一条"永远匹配不上"的孤儿白名单行 ——
     * 运维加了它、界面上看着加好了，实际谁都拿不到权限（同 T15-4 CIDR 的教训）。</p>
     */
    private void assertSubjectExists(String type, Long subjectId) {
        if (subjectId == null) {
            throw GatewayException.badRequest("主体ID不能为空");
        }
        if (SysInterfaceVisibility.TYPE_USER.equals(type)) {
            if (userMapper.selectById(subjectId) == null) {
                throw GatewayException.badRequest("用户不存在: id=" + subjectId);
            }
        } else {
            if (roleMapper.selectById(subjectId) == null) {
                throw GatewayException.badRequest("角色不存在: id=" + subjectId);
            }
        }
    }

    /** 唯一键 uk_iface_vis_subject：提前给出可读错误，避免 DB 抛 DuplicateKeyException 变成 500 */
    private void assertNotDuplicated(String type, Long subjectId, Long selfId) {
        List<SysInterfaceVisibility> same = mapper.selectList(
                new QueryWrapper<SysInterfaceVisibility>()
                        .eq("subject_type", type)
                        .eq("subject_id", subjectId));
        if (same == null) {
            return;
        }
        for (SysInterfaceVisibility row : same) {
            if (selfId == null || !selfId.equals(row.getId())) {
                throw GatewayException.badRequest("该主体已在白名单中：" + type + "#" + subjectId);
            }
        }
    }

    private Map<Long, SysUser> loadUsers(List<SysInterfaceVisibility> rows) {
        Set<Long> ids = rows.stream()
                .filter(e -> SysInterfaceVisibility.TYPE_USER.equals(e.getSubjectType()))
                .map(SysInterfaceVisibility::getSubjectId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        List<SysUser> list = userMapper.selectBatchIds(ids);
        Map<Long, SysUser> map = new HashMap<>();
        if (list != null) {
            for (SysUser u : list) {
                map.put(u.getId(), u);
            }
        }
        return map;
    }

    private Map<Long, SysRole> loadRoles(List<SysInterfaceVisibility> rows) {
        Set<Long> ids = rows.stream()
                .filter(e -> SysInterfaceVisibility.TYPE_ROLE.equals(e.getSubjectType()))
                .map(SysInterfaceVisibility::getSubjectId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        List<SysRole> list = roleMapper.selectBatchIds(ids);
        Map<Long, SysRole> map = new HashMap<>();
        if (list != null) {
            for (SysRole r : list) {
                map.put(r.getId(), r);
            }
        }
        return map;
    }
}
