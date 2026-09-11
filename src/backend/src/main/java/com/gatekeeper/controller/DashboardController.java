package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.SecurityEvent;
import com.gatekeeper.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据大屏 + 统计仪表盘 Controller
 *
 * <p>为管理端数据大屏与统计仪表盘提供聚合统计数据，业务模块包括：总体概览、
 * 调用趋势、应用/接口调用排行，以及最近安全事件。
 *
 * <p>主要接口路径前缀：{@code /dashboard/screen}
 * <ul>
 *   <li>GET /dashboard/screen/overview       总览统计</li>
 *   <li>GET /dashboard/screen/trend          调用趋势</li>
 *   <li>GET /dashboard/screen/app-rank       应用调用排行</li>
 *   <li>GET /dashboard/screen/interface-rank 接口调用排行</li>
 *   <li>GET /dashboard/screen/recent-events  最近安全事件</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Tag(name = "数据看板", description = "数据看板管理接口")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 获取仪表盘总览统计
     *
     * @return 各类核心指标的聚合数据（如应用数、接口数、今日调用量等）
     */
    @Operation(summary = "查询大屏总览")
    @GetMapping("/screen/overview")
    public Result<Map<String, Object>> screenOverview() {
        return Result.success(dashboardService.overview());
    }

    /**
     * 获取调用趋势数据
     *
     * @return 按时间维度聚合的调用量趋势列表
     */
    @Operation(summary = "查询调用趋势")
    @GetMapping("/screen/trend")
    public Result<List<Map<String, Object>>> screenTrend() {
        return Result.success(dashboardService.trend());
    }

    /**
     * 获取应用调用排行
     *
     * @return 按调用量排序的应用排行列表
     */
    @Operation(summary = "查询应用调用排行")
    @GetMapping("/screen/app-rank")
    public Result<List<Map<String, Object>>> appRank() {
        return Result.success(dashboardService.appRank());
    }

    /**
     * 获取接口调用排行
     *
     * @return 按调用量排序的接口排行列表
     */
    @Operation(summary = "查询接口热度")
    @GetMapping("/screen/interface-rank")
    public Result<List<Map<String, Object>>> interfaceRank() {
        return Result.success(dashboardService.interfaceRank());
    }

    /**
     * 获取最近安全事件
     *
     * @return 最近发生的安全事件列表
     */
    @Operation(summary = "查询最近安全事件")
    @GetMapping("/screen/recent-events")
    public Result<List<SecurityEvent>> recentEvents() {
        return Result.success(dashboardService.recentEvents());
    }
}
