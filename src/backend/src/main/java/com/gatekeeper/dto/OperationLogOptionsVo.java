package com.gatekeeper.dto;

import lombok.Data;

import java.util.List;

/**
 * 操作审计页「筛选下拉」候选项 VO
 *
 * <p>供 {@code GET /system/operation-log/filter-options} 返回，被审计页
 * （{@code SysLog.vue} / {@code PermAudit.vue}）的「操作类型」「操作模块」两个下拉消费。</p>
 *
 * <p><b>为什么由服务端下发而不在前端写死</b>：{@code operation_module} 由
 * {@code OperationLogAspect#firstSegment(requestURI)} 推导 —— 任何 controller 路径的首段
 * 都会成为一个模块值（命中 {@code MODULE_MAP} 取映射值，否则取首段大写），
 * 值域**随 controller 数量增长**。前端写死必然过期：2026-09-17 实测库里真实存在
 * <b>18</b> 个模块值，而前端硬编码的 5 项只覆盖其中 4 个 ⇒ 14 个值的记录筛不出来。
 * 同样地，前端写死的 {@code LOGIN}/{@code LOGOUT} 两个「操作类型」永远不会被写入。</p>
 *
 * <p>因此本 VO 只回**库里真实出现过**的值（DISTINCT），保证下拉与数据永不失同步。</p>
 *
 * <ul>
 *   <li>{@code modules} —— 操作模块候选（如 APP / INTERFACE / GROUP / API-VERSION …），升序</li>
 *   <li>{@code types}   —— 操作类型候选，按 {@code CREATE, UPDATE, DELETE} 的语义序在前，
 *       其余值追加在后</li>
 * </ul>
 *
 * @author GateKeeper
 * @since T18-OSS-2 (APIM V2)
 */
@Data
public class OperationLogOptionsVo {

    /** 操作模块候选（库里真实出现过的去重值，升序） */
    private List<String> modules;

    /** 操作类型候选（CREATE/UPDATE/DELETE 优先，其余追加） */
    private List<String> types;
}
