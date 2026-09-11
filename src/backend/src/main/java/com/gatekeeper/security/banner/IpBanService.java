package com.gatekeeper.security.banner;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.IpBan;
import com.gatekeeper.mapper.IpBanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * IP 封禁管理服务
 *
 * 封禁机制（对应 PRD「IP 封禁机制说明」章节）：
 * 1. 封禁类型：手动封禁（管理员操作）、自动封禁（安全规则触发，见 SecurityDetectionService）；
 * 2. 封禁范围：全局封禁（关联应用 appId 为空，对所有应用生效）、应用级封禁（关联应用不为空，仅对该应用生效）；
 * 3. 封禁生效：封禁信息写入 Redis 的 ip_ban:{ip}:{appId 或 global} 键并设置 TTL（剩余封禁秒数），
 *    同时同步落库 ip_ban 表；网关 IpBanCheckHandler 查询 Redis（O(1)）实时生效；
 * 4. 解封机制：管理员手动解封（清除 Redis + 更新数据库状态），或 Redis TTL 到期自动解封。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpBanService {

    private final IpBanMapper ipBanMapper;
    private final StringRedisTemplate redisTemplate;

    /**
     * 手动封禁 IP
     *
     * 同时写入 Redis（实时生效）与数据库 ip_ban 表（持久化）。
     *
     * @param ip         被封禁的 IP
     * @param appId      关联应用 ID，null 表示全局封禁
     * @param reason     封禁原因
     * @param durationMin 封禁时长（分钟）
     */
    public void banIp(String ip, Long appId, String reason, int durationMin) {
        // 写入Redis：键为 ip_ban:{ip}:{appId 或 global}，TTL 为封禁时长，网关实时查询生效
        String banKey = "ip_ban:" + ip + ":" + (appId != null ? appId : "global");
        redisTemplate.opsForValue().set(banKey, reason, durationMin, TimeUnit.MINUTES);

        // 写入数据库 ip_ban 表，持久化封禁记录
        IpBan ban = new IpBan();
        ban.setIpAddress(ip);
        ban.setAppId(appId);
        ban.setBanReason(reason);
        ban.setBanStartTime(LocalDateTime.now());
        ban.setBanEndTime(LocalDateTime.now().plusMinutes(durationMin));
        ban.setBanStatus(1); // 1 = 封禁中
        ban.setBanType("MANUAL"); // 手动封禁
        ban.setCreatedAt(LocalDateTime.now());
        ban.setUpdatedAt(LocalDateTime.now());
        ipBanMapper.insert(ban);

        log.info("IP banned: ip={}, appId={}, reason={}, duration={}min", ip, appId, reason, durationMin);
    }

    /**
     * 手动解封 IP
     *
     * 清除 Redis 封禁键并更新数据库封禁状态为「已解封」。
     *
     * @param banId 封禁记录 ID
     */
    public void unbanIp(Long banId) {
        IpBan ban = ipBanMapper.selectById(banId);
        // 记录不存在或已解封时直接返回
        if (ban == null || ban.getBanStatus() != 1) {
            return;
        }

        // 清除Redis封禁键，网关立即放行
        String banKey = "ip_ban:" + ban.getIpAddress() + ":" + (ban.getAppId() != null ? ban.getAppId() : "global");
        redisTemplate.delete(banKey);

        // 更新数据库封禁状态为已解封
        ban.setBanStatus(0); // 0 = 已解封
        ban.setUpdatedAt(LocalDateTime.now());
        ipBanMapper.updateById(ban);

        log.info("IP unbanned: ip={}, banId={}", ban.getIpAddress(), banId);
    }

    /**
     * 查询当前封禁中的记录列表
     *
     * 按封禁开始时间倒序返回所有状态为「封禁中」的记录。
     *
     * @return 封禁中的 IP 记录列表
     */
    public List<IpBan> getActiveBans() {
        return ipBanMapper.selectList(
                new QueryWrapper<IpBan>().eq("ban_status", 1).orderByDesc("ban_start_time")
        );
    }

    /**
     * 分页查询封禁记录
     *
     * 按封禁开始时间倒序分页返回全部封禁记录（含已解封）。
     *
     * @param current 当前页码
     * @param size    每页条数
     * @return 封禁记录分页结果
     */
    public PageResult<IpBan> pageBans(int current, int size) {
        Page<IpBan> page = new Page<>(current, size);
        ipBanMapper.selectPage(page,
                new QueryWrapper<IpBan>().orderByDesc("ban_start_time"));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 检查 IP 是否处于封禁状态
     *
     * 通过查询 Redis 封禁键是否存在来判断，O(1) 复杂度。
     *
     * @param ip    待检查的 IP
     * @param appId 关联应用 ID，null 表示检查全局封禁
     * @return true = 已封禁，false = 未封禁
     */
    public boolean isBanned(String ip, Long appId) {
        String banKey = "ip_ban:" + ip + ":" + (appId != null ? appId : "global");
        return Boolean.TRUE.equals(redisTemplate.hasKey(banKey));
    }
}
