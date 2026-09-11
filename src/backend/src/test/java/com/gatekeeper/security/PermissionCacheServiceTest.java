package com.gatekeeper.security;

import com.gatekeeper.mapper.SysMenuMapper;
import com.gatekeeper.mapper.SysRoleMenuMapper;
import com.gatekeeper.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PermissionCacheService 单元测试 — 覆盖缓存读写、回源 DB、失效用户/角色能力
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PermissionCacheService 缓存读写")
class PermissionCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private SetOperations<String, String> setOps;

    @Mock
    private SysUserRoleMapper sysUserRoleMapper;

    @Mock
    private SysRoleMenuMapper sysRoleMenuMapper;

    @Mock
    private SysMenuMapper sysMenuMapper;

    private PermissionCacheService service;

    @BeforeEach
    void setUp() {
        service = new PermissionCacheService(redisTemplate, sysUserRoleMapper,
                sysRoleMenuMapper, sysMenuMapper);
    }

    @Test
    @DisplayName("cacheKey 拼接符合 gk:perm:{uid} 规范")
    void cacheKey_formatIsCorrect() {
        assertEquals("gk:perm:1", PermissionCacheService.cacheKey(1L));
        assertEquals("gk:perm:42", PermissionCacheService.cacheKey(42L));
    }

    @Test
    @DisplayName("命中缓存时不查 DB")
    void getUserPerms_cacheHit_returnsCachedWithoutDb() {
        // 模拟缓存里有 2 个权限点
        Set<String> cached = new HashSet<>();
        cached.add("app:list");
        cached.add("app:create");
        when(redisTemplate.hasKey("gk:perm:100")).thenReturn(true);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("gk:perm:100")).thenReturn(cached);

        Set<String> result = service.getUserPerms(100L);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains("app:list"));
        // 不应回源 DB
        verify(sysUserRoleMapper, never()).selectList(any());
        verify(sysRoleMenuMapper, never()).selectList(any());
        verify(sysMenuMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("缓存未命中时回源 DB 并写回缓存")
    void getUserPerms_cacheMiss_fallsBackToDbAndWritesCache() {
        // 缓存不存在（EXISTS 返回 false，跳过 SMEMBERS）
        when(redisTemplate.hasKey("gk:perm:200")).thenReturn(false);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        // DB 回源：用户 200 有角色 1，角色 1 关联菜单 1，菜单 1 是权限点 app:disable
        com.gatekeeper.entity.SysUserRole ur = new com.gatekeeper.entity.SysUserRole();
        ur.setUserId(200L);
        ur.setRoleId(1L);
        when(sysUserRoleMapper.selectList(any())).thenReturn(Collections.singletonList(ur));

        com.gatekeeper.entity.SysRoleMenu rm = new com.gatekeeper.entity.SysRoleMenu();
        rm.setRoleId(1L);
        rm.setMenuId(1L);
        when(sysRoleMenuMapper.selectList(any())).thenReturn(Collections.singletonList(rm));

        com.gatekeeper.entity.SysMenu m1 = new com.gatekeeper.entity.SysMenu();
        m1.setId(1L);
        m1.setType(3);
        m1.setStatus(1);
        m1.setPermCode("app:disable");
        when(sysMenuMapper.selectList(any())).thenReturn(Collections.singletonList(m1));

        Set<String> result = service.getUserPerms(200L);

        assertNotNull(result);
        assertTrue(result.contains("app:disable"));

        // 回源后应当写回 Redis
        verify(redisTemplate, atLeastOnce()).delete("gk:perm:200");
        verify(setOps, times(1)).add(eq("gk:perm:200"), any(String.class));
        verify(redisTemplate, times(1)).expire(eq("gk:perm:200"), eq(24L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("invalidateUser 删除指定用户缓存")
    void invalidateUser_deletesKey() {
        when(redisTemplate.delete("gk:perm:7")).thenReturn(true);
        service.invalidateUser(7L);
        verify(redisTemplate, times(1)).delete("gk:perm:7");
    }

    @Test
    @DisplayName("invalidateRole 反查用户并逐个 DEL")
    void invalidateRole_iteratesUsers() {
        // 角色 5 下有用户 10、11
        com.gatekeeper.entity.SysUserRole u1 = new com.gatekeeper.entity.SysUserRole();
        u1.setUserId(10L);
        u1.setRoleId(5L);
        com.gatekeeper.entity.SysUserRole u2 = new com.gatekeeper.entity.SysUserRole();
        u2.setUserId(11L);
        u2.setRoleId(5L);
        when(sysUserRoleMapper.selectList(any()))
                .thenReturn(java.util.Arrays.asList(u1, u2));
        when(redisTemplate.delete(anyString())).thenReturn(true);

        service.invalidateRole(5L);

        verify(redisTemplate, times(1)).delete("gk:perm:10");
        verify(redisTemplate, times(1)).delete("gk:perm:11");
    }

    @Test
    @DisplayName("getUserPerms 收到 null uid 返回空集合")
    void getUserPerms_nullUid_returnsEmpty() {
        Set<String> result = service.getUserPerms(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("用户没有任何角色时返回空集合")
    void getUserPerms_noRoles_returnsEmpty() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(sysUserRoleMapper.selectList(any())).thenReturn(Collections.emptyList());

        Set<String> result = service.getUserPerms(999L);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
