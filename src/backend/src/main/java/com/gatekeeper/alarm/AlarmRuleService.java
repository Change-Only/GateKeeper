package com.gatekeeper.alarm;

import com.gatekeeper.entity.AlarmRule;

import java.util.List;

/**
 * 告警规则服务接口 — T04-C 告警域（规则主数据 + 实时/离线评估调度入口）
 *
 * <h3>关键约定</h3>
 * <ul>
 *   <li>scopeType: 1=按对象(应用/接口)评估, 2=平台全局评估</li>
 *   <li>threshold: 阈值表达式，如 {@code >5} / {@code >200%} / {@code <10}</li>
 *   <li>评估逻辑由 {@link #evaluateRealtime()} / {@link #evaluateOffline()} 驱动，
 *       实际由 {@code AlarmEvaluateJob} 周期性调度，fail-open。</li>
 * </ul>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
public interface AlarmRuleService {

    /**
     * 查询告警规则列表。
     *
     * @param status 状态过滤（可空，1=启用, 0=停用）
     * @return 规则列表
     */
    List<AlarmRule> list(Integer status);

    /**
     * 按主键查询规则详情。
     *
     * @param id 规则 ID
     * @return 规则实体
     */
    AlarmRule get(Long id);

    /**
     * 创建告警规则。
     *
     * @param rule 入参（ruleName/alarmType/threshold 必填）
     * @return 新建规则（含自增 ID）
     */
    AlarmRule create(AlarmRule rule);

    /**
     * 修改告警规则。
     *
     * @param id   规则 ID
     * @param rule 入参（仅非空字段被更新）
     */
    void update(Long id, AlarmRule rule);

    /**
     * 启用/停用规则。
     *
     * @param id     规则 ID
     * @param status 1=启用, 0=停用
     */
    void toggle(Long id, Integer status);

    /**
     * 发送测试告警（通过规则已配置渠道）。
     *
     * @param id 规则 ID
     */
    void test(Long id);

    /**
     * 实时评估（对象级 scopeType=1 的启用规则）。由 {@code AlarmEvaluateJob} 每 10s 调度。
     */
    void evaluateRealtime();

    /**
     * 离线评估（全局级 scopeType=2 的启用规则）。由 {@code AlarmEvaluateJob} 每 5 分钟调度。
     */
    void evaluateOffline();
}
