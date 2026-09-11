package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.SysUser;
import com.gatekeeper.mapper.SysUserMapper;
import com.gatekeeper.security.PermissionCacheService;
import com.gatekeeper.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 认证接口 — 管理后台登录/登出
 *
 * <p>登录校验：用户名 + BCrypt 密码比对；连续失败 5 次锁定账号 10 分钟（Redis 计数）。
 * 登录成功签发 JWT 令牌（默认 120 分钟有效）。</p>
 *
 * <p>T02 增强：登录成功后通过 {@link PermissionCacheService#getUserPerms(Long)} 拉取
 * 用户权限点并写入 Redis gk:perm:{uid}（不在 JWT 里塞权限，避免 token 膨胀）。</p>
 *
 * <p>路径说明：应用已配置 context-path=/api，类级别路径不再重复 /api 前缀，
 * 实际对外路径为 POST /api/auth/login。</p>
 * <ul>
 *   <li>POST /auth/login — 登录（对外 /api/auth/login）</li>
 *   <li>POST /auth/logout — 登出（前端清除令牌即可，此处预留审计扩展）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "认证登录", description = "认证登录管理接口")
public class AuthController {

    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final PermissionCacheService permissionCacheService;

    /** 登录失败锁定阈值（默认 5 次） */
    @Value("${gatekeeper.security.login-fail-threshold:5}")
    private int failThreshold;

    /** 锁定时长（分钟，默认 10 分钟） */
    @Value("${gatekeeper.security.login-lock-minutes:10}")
    private int lockMinutes;

    /**
     * 管理员登录：校验账号密码，失败计数并锁定，成功签发 JWT。
     *
     * <p>T02 改进：登录成功后立即加载用户权限点集合到 Redis gk:perm:{uid}（24 小时 TTL），
     * 后续 PermissionInterceptor 直接读 Redis 校验，不再走 DB。</p>
     *
     * @param body    {"username": "xxx", "password": "xxx"}
     * @param request HTTP 请求（用于记录登录 IP）
     * @return 登录成功返回令牌与用户信息；失败返回统一错误
     */
    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return Result.error(400, "请输入账号和密码");
        }

        String clientIp = request.getRemoteAddr();
        String lockKey = "login_lock:" + username;

        // 检查账号是否处于锁定状态
        String locked = redisTemplate.opsForValue().get(lockKey);
        if (locked != null) {
            Long ttl = redisTemplate.getExpire(lockKey);
            return Result.error(423, "账号已锁定，请 " + (ttl != null ? ttl / 60 + 1 : "") + " 分钟后重试");
        }

        // 按用户名查询用户
        SysUser user = sysUserMapper.selectOne(new QueryWrapper<SysUser>().eq("username", username));
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            recordFailure(username, clientIp, lockKey);
            return Result.error(401, "账号或密码错误");
        }

        // BCrypt 密码比对
        boolean ok;
        try {
            ok = BCrypt.checkpw(password, user.getPassword());
        } catch (Exception e) {
            log.error("BCrypt verify error for user={}", username);
            ok = false;
        }

        if (!ok) {
            recordFailure(username, clientIp, lockKey);
            return Result.error(401, "账号或密码错误");
        }

        // 登录成功：清除失败计数并签发令牌
        redisTemplate.delete("login_fail:" + username);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        // ============ T02：登录后立即加载权限点到 Redis ============
        // 1) 拉权限点（读 Redis cache，未命中回源 DB 并写回）
        // 2) PermissionCacheService 内部已完成 Redis 写入，无需手动 SET
        Set<String> perms = permissionCacheService.getUserPerms(user.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("username", user.getUsername());
        userInfo.put("realName", user.getRealName());
        data.put("user", userInfo);
        // 为前端展示方便，返回一份 permCode 列表（这只是缓存快照，实际校验仍在服务端 PermissionInterceptor）
        data.put("perms", perms);
        data.put("permCount", perms == null ? 0 : perms.size());

        log.info("Admin login success: user={}, uid={}, ip={}, permCount={}",
                username, user.getId(), clientIp, perms == null ? 0 : perms.size());
        return Result.success(data);
    }

    /**
     * 记录一次登录失败；达到阈值后锁定账号
     */
    private void recordFailure(String username, String clientIp, String lockKey) {
        String failKey = "login_fail:" + username;
        Long count = redisTemplate.opsForValue().increment(failKey);
        if (count != null && count == 1) {
            // 首次失败启动统计窗口（10 分钟）
            redisTemplate.expire(failKey, 10, TimeUnit.MINUTES);
        }
        if (count != null && count >= failThreshold) {
            // 达到阈值：锁定账号并清空计数
            redisTemplate.opsForValue().set(lockKey, "locked", lockMinutes, TimeUnit.MINUTES);
            redisTemplate.delete(failKey);
            log.warn("Account locked: user={}, ip={}, failures={}", username, clientIp, count);
        }
    }
}
