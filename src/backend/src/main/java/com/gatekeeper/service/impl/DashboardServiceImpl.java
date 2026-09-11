package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.Alert;
import com.gatekeeper.entity.ApiCallLog;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.entity.AppCredential;
import com.gatekeeper.entity.SecurityEvent;
import com.gatekeeper.mapper.AlertMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.mapper.AppCredentialMapper;
import com.gatekeeper.security.banner.IpBanService;
import com.gatekeeper.service.CallLogService;
import com.gatekeeper.service.DashboardService;
import com.gatekeeper.service.SecurityEventService;
import com.gatekeeper.vo.RiskVo;
import com.gatekeeper.vo.TodoItemVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据大屏 + 统计仪表盘服务实现 — 提供当日调用量、成功率、限流/拦截数、安全事件、封禁数等
 * 核心指标概览，以及 24 小时调用趋势、应用/接口调用量排行和最近安全事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** 待办口径：密钥到期预警窗口（天） */
    private static final int CRED_EXPIRE_WARN_DAYS = 30;

    /** 密钥启用中状态 */
    private static final int CRED_STATUS_ENABLED = 1;

    /** 告警未读状态（计入未处理） */
    private static final int ALERT_STATUS_UNREAD = 0;

    /** 告警已读未处理状态（计入未处理） */
    private static final int ALERT_STATUS_READ = 1;

    private final CallLogService callLogService;
    private final IpBanService ipBanService;
    private final SecurityEventService securityEventService;

    // ===== T06-A：概览页聚合计数所需 Mapper（SQL 预聚合，避免全表查询） =====
    private final ApiInterfaceMapper apiInterfaceMapper;
    private final AppApiGrantMapper appApiGrantMapper;
    private final AppCredentialMapper appCredentialMapper;
    private final AlertMapper alertMapper;

    /**
     * 统计当日核心指标概览（调用量、成功率、限流/拦截/安全事件/封禁数量）
     *
     * @return 各项指标的键值对集合
     */
    @Override
    public Map<String, Object> overview() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay(); // 当日 0 点
        LocalDateTime endOfDay = LocalDate.now().plusDays(1).atStartOfDay(); // 次日 0 点

        long totalCalls = callLogService.count(
                new QueryWrapper<ApiCallLog>().ge("request_time", startOfDay).lt("request_time", endOfDay));
        long successCalls = callLogService.count(
                new QueryWrapper<ApiCallLog>()
                        .ge("request_time", startOfDay).lt("request_time", endOfDay)
                        .eq("is_blocked", false).eq("is_rate_limited", false)
                        .ge("response_status", 200).lt("response_status", 300)); // 未被拦截/限流且 2xx 视为成功
        long rateLimited = callLogService.count(
                new QueryWrapper<ApiCallLog>()
                        .ge("request_time", startOfDay).lt("request_time", endOfDay)
                        .eq("is_rate_limited", true));
        long blocked = callLogService.count(
                new QueryWrapper<ApiCallLog>()
                        .ge("request_time", startOfDay).lt("request_time", endOfDay)
                        .eq("is_blocked", true));
        long securityEvents = securityEventService.count(
                new QueryWrapper<SecurityEvent>()
                        .ge("occurred_at", startOfDay).lt("occurred_at", endOfDay));
        long activeBans = ipBanService.getActiveBans().size();

        double successRate = totalCalls > 0 ? (double) successCalls / totalCalls * 100 : 100.0;

        Map<String, Object> result = new HashMap<>();
        result.put("totalCalls", totalCalls);
        result.put("successRate", Math.round(successRate * 100) / 100.0); // 成功率保留两位小数
        result.put("rateLimitedCount", rateLimited);
        result.put("blockedCount", blocked);
        result.put("securityEventCount", securityEvents);
        result.put("activeBanCount", activeBans);
        return result;
    }

    /**
     * 统计最近 24 小时逐小时调用量趋势
     *
     * @return 每小时的调用量数据点列表（含小时与计数）
     */
    @Override
    public List<Map<String, Object>> trend() {
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 23; i >= 0; i--) {
            LocalDateTime hourStart = now.minusHours(i).withMinute(0).withSecond(0).withNano(0); // 对齐到整点
            LocalDateTime hourEnd = hourStart.plusHours(1);
            long count = callLogService.count(
                    new QueryWrapper<ApiCallLog>()
                            .ge("request_time", hourStart).lt("request_time", hourEnd));
            Map<String, Object> point = new HashMap<>();
            point.put("hour", hourStart.format(DateTimeFormatter.ofPattern("HH:mm")));
            point.put("count", count);
            result.add(point);
        }
        return result;
    }

    /**
     * 统计当日应用调用量排行（Top 10）
     *
     * @return 按调用量降序的应用排行列表
     */
    @Override
    public List<Map<String, Object>> appRank() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = LocalDate.now().plusDays(1).atStartOfDay();

        List<ApiCallLog> logs = callLogService.list(
                new QueryWrapper<ApiCallLog>()
                        .ge("request_time", startOfDay).lt("request_time", endOfDay)
                        .select("app_id", "app_name"));

        Map<String, Long> counts = new HashMap<>();
        Map<String, Long> appIds = new HashMap<>();
        for (ApiCallLog log : logs) {
            String name = log.getAppName() != null ? log.getAppName() : "未知";
            counts.merge(name, 1L, Long::sum); // 按应用名累加调用次数
            if (log.getAppId() != null) {
                appIds.put(name, log.getAppId()); // 记录应用名对应的 ID
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()) // 按调用量降序
                .limit(10) // 取前 10 名
                .forEach(e -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("appName", e.getKey());
                    item.put("callCount", e.getValue());
                    item.put("appId", appIds.get(e.getKey()));
                    result.add(item);
                });
        return result;
    }

    /**
     * 统计当日接口调用量排行（Top 10）
     *
     * @return 按调用量降序的接口排行列表
     */
    @Override
    public List<Map<String, Object>> interfaceRank() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = LocalDate.now().plusDays(1).atStartOfDay();

        List<ApiCallLog> logs = callLogService.list(
                new QueryWrapper<ApiCallLog>()
                        .ge("request_time", startOfDay).lt("request_time", endOfDay)
                        .select("interface_id", "interface_path"));

        Map<String, Long> counts = new HashMap<>();
        for (ApiCallLog log : logs) {
            String path = log.getInterfacePath() != null ? log.getInterfacePath() : "未知";
            counts.merge(path, 1L, Long::sum); // 按接口路径累加调用次数
        }

        List<Map<String, Object>> result = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()) // 按调用量降序
                .limit(10) // 取前 10 名
                .forEach(e -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("interfacePath", e.getKey());
                    item.put("callCount", e.getValue());
                    result.add(item);
                });
        return result;
    }

    /**
     * 查询最近 10 条安全事件
     *
     * @return 安全事件列表（按发生时间倒序）
     */
    @Override
    public List<SecurityEvent> recentEvents() {
        return securityEventService.list(
                new QueryWrapper<SecurityEvent>()
                        .orderByDesc("occurred_at").last("LIMIT 10"));
    }

    // =====================================================================
    // T06-A：概览页待办 / 风险聚合（PRD P0 新增，SQL 预聚合计数）
    // =====================================================================

    /**
     * 概览页待办事项 —— 固定 4 项、顺序固定，各 count 均下推到 SQL 聚合。
     *
     * <p>计数口径：
     * <ul>
     *   <li>{@code api}   api_interface.publish_status = 1（待审核）</li>
     *   <li>{@code grant} app_api_grant.status = 0（待审批）</li>
     *   <li>{@code cred}  app_credential.status = 1 且 expire_time 非空且落在 (now, now+30d]</li>
     *   <li>{@code alarm} alert.status IN (0,1)（未读 / 已读未处理）</li>
     * </ul>
     * 使用 {@code selectCount} 生成 {@code SELECT COUNT(*)}，禁止将全表查回内存再 size()。</p>
     *
     * @return 4 项待办（顺序：api / grant / cred / alarm）
     */
    @Override
    public List<TodoItemVo> todo() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime horizon = now.plusDays(CRED_EXPIRE_WARN_DAYS); // 30 天到期窗口

        // 待审核接口
        long apiCount = apiInterfaceMapper.selectCount(
                new QueryWrapper<ApiInterface>().eq("publish_status", 1));

        // 待审批授权
        long grantCount = appApiGrantMapper.selectCount(
                new QueryWrapper<AppApiGrant>().eq("status", 0));

        // 密钥即将过期：启用中 + 有过期时间 + 落在 (now, now+30d]（NULL 与已停用/吊销/过期均不计入）
        long credCount = appCredentialMapper.selectCount(
                new QueryWrapper<AppCredential>()
                        .eq("status", CRED_STATUS_ENABLED)
                        .isNotNull("expire_time")
                        .gt("expire_time", now)
                        .le("expire_time", horizon));

        // 未处理告警（未读 / 已读未处理）
        long alarmCount = alertMapper.selectCount(
                new QueryWrapper<Alert>().in("status", ALERT_STATUS_UNREAD, ALERT_STATUS_READ));

        List<TodoItemVo> list = new ArrayList<>(4);
        list.add(new TodoItemVo("api", "待审核接口", apiCount,
                "待审核状态（publish_status=1）的接口", "/api/api-list"));
        list.add(new TodoItemVo("grant", "待审批授权", grantCount,
                "待审批状态（status=0）的接口授权申请", "/perm/perm-matrix"));
        list.add(new TodoItemVo("cred", "密钥即将过期", credCount,
                "30 天内到期且处于启用中（status=1）的密钥", "/app"));
        list.add(new TodoItemVo("alarm", "未处理告警", alarmCount,
                "未读或已读未处理（status 为 0/1）的告警", "/mon/mon-alarm"));

        log.debug("Dashboard todo: api={}, grant={}, cred={}, alarm={}",
                apiCount, grantCount, credCount, alarmCount);
        return list;
    }

    /**
     * 概览页风险三档分级 —— 按 alert.level 对「未处理告警」分级计数。
     *
     * <p>level 为字符串枚举 INFO/WARNING/CRITICAL（与真实数据、前端枚举一致）。</p>
     *
     * @return {high=CRITICAL 数, mid=WARNING 数, low=INFO 数}
     */
    @Override
    public RiskVo risk() {
        long high = alertMapper.selectCount(
                new QueryWrapper<Alert>()
                        .eq("level", "CRITICAL")
                        .in("status", ALERT_STATUS_UNREAD, ALERT_STATUS_READ));
        long mid = alertMapper.selectCount(
                new QueryWrapper<Alert>()
                        .eq("level", "WARNING")
                        .in("status", ALERT_STATUS_UNREAD, ALERT_STATUS_READ));
        long low = alertMapper.selectCount(
                new QueryWrapper<Alert>()
                        .eq("level", "INFO")
                        .in("status", ALERT_STATUS_UNREAD, ALERT_STATUS_READ));

        log.debug("Dashboard risk: high={}, mid={}, low={}", high, mid, low);
        return new RiskVo(high, mid, low);
    }
}
