package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiCallLog;
import com.gatekeeper.entity.SecurityEvent;
import com.gatekeeper.security.banner.IpBanService;
import com.gatekeeper.service.CallLogService;
import com.gatekeeper.service.DashboardService;
import com.gatekeeper.service.SecurityEventService;
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

    private final CallLogService callLogService;
    private final IpBanService ipBanService;
    private final SecurityEventService securityEventService;

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
}
