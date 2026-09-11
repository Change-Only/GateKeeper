package com.gatekeeper.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 概览页「待办事项」项 VO —— 对应 PRD 概览页待办卡片（T06-A）
 *
 * <p>由 {@code GET /api/dashboard/todo} 返回，固定 4 项、顺序固定：
 * <ol>
 *   <li>{@code api}   待审核接口      → 路由 {@code /api/api-list}</li>
 *   <li>{@code grant} 待审批授权      → 路由 {@code /perm/perm-matrix}</li>
 *   <li>{@code cred}  密钥即将过期    → 路由 {@code /app}</li>
 *   <li>{@code alarm} 未处理告警      → 路由 {@code /mon/mon-alarm}</li>
 * </ol>
 * 前端据此渲染可点击跳转的待办卡片，{@code count} 为各域「真正待办」的预聚合计数
 * （非资源总数），这是本轮 P0 缺陷的核心修复点。</p>
 *
 * @author GateKeeper
 * @since T06 (APIM V2)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TodoItemVo {

    /** 待办类型：api / grant / cred / alarm */
    private String type;

    /** 待办标题（中文） */
    private String label;

    /** 待办数量（SQL 聚合计数） */
    private long count;

    /** 一句中文说明（口径提示） */
    private String desc;

    /** 前端点击跳转路由 */
    private String route;
}
