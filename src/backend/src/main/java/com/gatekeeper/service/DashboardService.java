package com.gatekeeper.service;

import com.gatekeeper.entity.SecurityEvent;
import com.gatekeeper.vo.RiskVo;
import com.gatekeeper.vo.TodoItemVo;

import java.util.List;
import java.util.Map;

/**
 * 数据大屏 + 统计仪表盘服务接口 — 负责网关运营概览、访问趋势、应用/接口排行及近期安全事件的统计展示业务
 */
public interface DashboardService {

    /**
     * 查询运营总览数据（含应用数、接口数、今日调用量、安全事件数等核心指标）
     *
     * @return 总览指标键值对集合
     */
    Map<String, Object> overview();

    /**
     * 查询访问趋势统计数据（如按时间维度的调用量变化）
     *
     * @return 趋势统计数据列表
     */
    List<Map<String, Object>> trend();

    /**
     * 查询应用调用量排行
     *
     * @return 应用排行统计数据列表
     */
    List<Map<String, Object>> appRank();

    /**
     * 查询接口调用量排行
     *
     * @return 接口排行统计数据列表
     */
    List<Map<String, Object>> interfaceRank();

    /**
     * 查询近期安全事件列表
     *
     * @return 近期安全事件列表
     */
    List<SecurityEvent> recentEvents();

    /**
     * 查询概览页待办事项（T06-A，PRD P0 新增聚合接口）。
     *
     * <p>固定返回 4 项、顺序固定：待审核接口 / 待审批授权 / 密钥即将过期 / 未处理告警。
     * 各项 count 均为 SQL 预聚合，非资源总数。</p>
     *
     * @return 待办事项列表（4 项）
     */
    List<TodoItemVo> todo();

    /**
     * 查询概览页风险三档分级（T06-A，PRD P0 新增聚合接口）。
     *
     * <p>按 alert.level（CRITICAL/WARNING/INFO）统计未处理告警数量。</p>
     *
     * @return 风险分级结果 {high, mid, low}
     */
    RiskVo risk();
}
