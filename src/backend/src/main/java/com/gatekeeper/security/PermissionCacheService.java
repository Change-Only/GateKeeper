package com.gatekeeper.security;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.SysMenu;
import com.gatekeeper.entity.SysRoleMenu;
import com.gatekeeper.entity.SysUserRole;
import com.gatekeeper.mapper.SysMenuMapper;
import com.gatekeeper.mapper.SysRoleMenuMapper;
import com.gatekeeper.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 权限点缓存服务 — 基于 Redis 的 Cache-Aside 实现
 *
 * <p>设计要点（架构 §4.5 + 总纲决策 5）：
 * <ul>
 *   <li>Redis key 格式：{@code gk:perm:{uid}}，value 是 String Set 类型</li>
 *   <li>登录后通过 {@link #getUserPerms(Long)} 加载（先用缓存，未命中回源 DB）</li>
 *   <li>权限变更主动失效：
 *     <ul>
 *       <li>{@link #invalidateUser(Long)} — DEL 指定用户缓存</li>
 *       <li>{@link #invalidateRole(Long)} — 按 sys_user_role 找用户列表，逐个 DEL</li>
 *       <li>{@link #afterCommitInvalidateUser(Long)} — 事务 afterCommit 包装</li>
 *       <li>{@link #afterCommitInvalidateRole(Long)} — 事务 afterCommit 包装</li>
 *     </ul>
 *   </li>
 *   <li>TTL = 24 小时，权限变更时主动 DEL 兜底（不依赖 TTL 自动过期）</li>
 * </ul>
 * </p>
 *
 * @author GateKeeper
 * @since T02 (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionCacheService {

    /** Redis key 前缀 */
    public static final String CACHE_KEY_PREFIX = "gk:perm:";

    /** 缓存 TTL（小时） */
    public static final long CACHE_TTL_HOURS = 24L;

    private final StringRedisTemplate redisTemplate;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysMenuMapper sysMenuMapper;

    // ============================================================
    // 缓存 key 工具
    // ============================================================

    /**
     * 拼接 Redis key。
     *
     * @param uid 用户 ID
     * @return Redis key，例如 {@code gk:perm:1}
     */
    public static String cacheKey(Long uid) {
        return CACHE_KEY_PREFIX + uid;
    }

    // ============================================================
    // 读取
    // ============================================================

    /**
     * 读取用户权限点集合（先读 Redis，缓存未命中回源 DB 并写回 Redis）。
     *
     * <p>缓存"未命中"的判断：{@link SetOperations#members(Object)} 在 Redis 不存在该 key
     * 时返回空集合（非 null），不能简单用 {@code != null} 判断。改用 {@link StringRedisTemplate#hasKey}
     * 显式判 key 存在性（Redis EXISTS）。</p>
     *
     * @param uid 用户 ID
     * @return 权限点编码集合；用户无任何权限时返回空集合（非 null）
     */
    public Set<String> getUserPerms(Long uid) {
        if (uid == null) {
            return Collections.emptySet();
        }
        String key = cacheKey(uid);

        // 1) 先用 EXISTS 精确判断缓存是否存在
        try {
            Boolean hasKey = redisTemplate.hasKey(key);
            if (Boolean.TRUE.equals(hasKey)) {
                Set<String> cached = readFromCache(key);
                if (cached != null && !cached.isEmpty()) {
                    return cached;
                }
                if (cached != null) {
                    // key 存在但是空集合（理论上不应该发生，兼容处理）
                    return cached;
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to probe perm cache for uid={}, fallback to DB. cause={}",
                    uid, ex.getMessage());
        }

        // 2) 缓存未命中或读失败 → 回源 DB
        Set<String> perms = loadPermsFromDb(uid);

        // 3) 写回缓存（即使为空也写入，让"无权限"语义可缓存，避免热用户穿透）
        try {
            writeToCache(key, perms);
        } catch (Exception ex) {
            log.warn("Failed to write perm cache for uid={}, skip. cause={}",
                    uid, ex.getMessage());
        }

        return perms;
    }

    /**
     * 直接从 Redis 读取 perm 集合；缓存不存在时返回 null（与"空集合"区分）。
     */
    private Set<String> readFromCache(String key) {
        Set<String> members = redisTemplate.opsForSet().members(key);
        if (members == null) {
            return null; // 区分"未缓存"与"空集合"
        }
        return members;
    }

    /**
     * 写入 Redis（24 小时 TTL）。
     */
    private void writeToCache(String key, Set<String> perms) {
        // opsForSet.add 不支持 TTL 原子设置，需分两步（先清理再 add 再 expire）
        redisTemplate.delete(key);
        if (perms != null && !perms.isEmpty()) {
            redisTemplate.opsForSet().add(key, perms.toArray(new String[0]));
            redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
        }
    }

    /**
     * 从 DB 加载权限点（user → roles → role_menu → menu.perm_code）。
     */
    private Set<String> loadPermsFromDb(Long uid) {
        // 1) 用户的所有角色 ID
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
                new QueryWrapper<SysUserRole>().eq("user_id", uid));
        if (userRoles == null || userRoles.isEmpty()) {
            return new HashSet<>();
        }
        Set<Long> roleIds = userRoles.stream()
                .map(SysUserRole::getRoleId)
                .collect(Collectors.toSet());

        // 2) 这些角色关联的菜单 ID
        List<SysRoleMenu> roleMenus = sysRoleMenuMapper.selectList(
                new QueryWrapper<SysRoleMenu>().in("role_id", roleIds));
        if (roleMenus == null || roleMenus.isEmpty()) {
            return new HashSet<>();
        }
        Set<Long> menuIds = roleMenus.stream()
                .map(SysRoleMenu::getMenuId)
                .collect(Collectors.toSet());

        // 3) 菜单的 perm_code（只取 type=3 权限点，且 status=1）
        List<SysMenu> menus = sysMenuMapper.selectList(
                new QueryWrapper<SysMenu>()
                        .in("id", menuIds)
                        .eq("type", 3)
                        .eq("status", 1)
                        .isNotNull("perm_code"));
        if (menus == null || menus.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> perms = new HashSet<>();
        for (SysMenu m : menus) {
            if (m.getPermCode() != null && !m.getPermCode().isEmpty()) {
                perms.add(m.getPermCode());
            }
        }
        return perms;
    }

    // ============================================================
    // 失效（事务外 / 事务内两种用法）
    // ============================================================

    /**
     * 立即失效单个用户的权限缓存（事务外使用）。
     */
    public void invalidateUser(Long uid) {
        if (uid == null) {
            return;
        }
        try {
            Boolean deleted = redisTemplate.delete(cacheKey(uid));
            log.info("Invalidated perm cache: uid={}, deleted={}", uid, deleted);
        } catch (Exception ex) {
            log.warn("Failed to invalidate perm cache for uid={}, cause={}",
                    uid, ex.getMessage());
        }
    }

    /**
     * 立即失效某个角色下所有用户的权限缓存（事务外使用）。
     *
     * @param roleId 角色 ID
     */
    public void invalidateRole(Long roleId) {
        if (roleId == null) {
            return;
        }
        // 查该角色下所有用户，逐个 DEL
        List<SysUserRole> users = sysUserRoleMapper.selectList(
                new QueryWrapper<SysUserRole>().eq("role_id", roleId));
        if (users == null || users.isEmpty()) {
            return;
        }
        for (SysUserRole ur : users) {
            invalidateUser(ur.getUserId());
        }
        log.info("Invalidated perm cache: roleId={}, users={}", roleId, users.size());
    }

    /**
     * 事务 afterCommit 后失效单个用户的权限缓存（事务内使用，确保事务提交后才 DEL）。
     *
     * <p>写入操作（用户角色调整）的 Controller/Service 层调用本方法。</p>
     */
    public void afterCommitInvalidateUser(Long uid) {
        registerAfterCommit(() -> invalidateUser(uid));
    }

    /**
     * 事务 afterCommit 后失效某个角色下所有用户的权限缓存（事务内使用）。
     *
     * <p>SysRoleMenuController 角色授权替换等操作调用本方法。</p>
     */
    public void afterCommitInvalidateRole(Long roleId) {
        registerAfterCommit(() -> invalidateRole(roleId));
    }

    /**
     * 注册 afterCommit 钩子（在事务内调用，无事务时立即执行）。
     */
    private void registerAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                task.run();
                            } catch (Exception ex) {
                                log.warn("afterCommit invalidate task failed: {}", ex.getMessage());
                            }
                        }
                    });
        } else {
            // 非事务上下文，立即执行
            task.run();
        }
    }

    // ============================================================
    // 写入辅助（事务内 + afterCommit 一步到位）
    // ============================================================

    /**
     * 在事务内先写 DB，再注册 afterCommit DEL（避免事务回滚但缓存已删的不一致）。
     *
     * @param uid        用户 ID
     * @param newRoleIds 要设置的角色 ID 列表（空集合表示移除全部角色）
     */
    @Transactional
    public void replaceUserRoles(Long uid, List<Long> newRoleIds) {
        // 1) 删旧关联
        sysUserRoleMapper.delete(
                new QueryWrapper<SysUserRole>().eq("user_id", uid));
        // 2) 插新关联
        if (newRoleIds != null) {
            for (Long roleId : newRoleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(uid);
                ur.setRoleId(roleId);
                sysUserRoleMapper.insert(ur);
            }
        }
        // 3) afterCommit DEL 缓存（事务回滚不执行）
        afterCommitInvalidateUser(uid);
    }

    /**
     * 在事务内替换角色的菜单授权集合。
     *
     * @param roleId     角色 ID
     * @param newMenuIds 新的菜单 ID 列表
     */
    @Transactional
    public void replaceRoleMenus(Long roleId, List<Long> newMenuIds) {
        // 1) 删旧关联
        sysRoleMenuMapper.delete(
                new QueryWrapper<SysRoleMenu>().eq("role_id", roleId));
        // 2) 插新关联
        if (newMenuIds != null) {
            for (Long menuId : newMenuIds) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setRoleId(roleId);
                rm.setMenuId(menuId);
                sysRoleMenuMapper.insert(rm);
            }
        }
        // 3) afterCommit 失效该角色下全部用户缓存
        afterCommitInvalidateRole(roleId);
    }
}
