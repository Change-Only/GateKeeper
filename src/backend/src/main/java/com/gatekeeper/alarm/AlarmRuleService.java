package com.gatekeeper.alarm;

import com.gatekeeper.dto.AlarmTargetVo;
import com.gatekeeper.entity.AlarmRule;

import java.util.List;

/**
 * 告警规则服务接口 — T04-C 告警域（规则主数据 + 实时/离线评估调度入口）
 *
 * <h3>关键约定</h3>
 * <ul>
 *   <li>scopeType: 1=按对象(应用/接口)评估, 2=平台全局评估</li>
 *   <li>targetType: T11 新增，把「按对象」落到实处 —— {@link #TARGET_TYPE_APP} 按应用 /
 *       {@link #TARGET_TYPE_API} 按接口；scopeType=1 时必填，scopeType=2 时后端归一为 null</li>
 *   <li>targetIds: T11 新增，逗号分隔对象ID；<b>空/null = 该维度下全部对象</b>，
 *       约定与 channelIds / receiverIds 一致</li>
 *   <li>threshold: 阈值表达式，如 {@code >5} / {@code >200%} / {@code <10}</li>
 *   <li>评估逻辑由 {@link #evaluateRealtime()} / {@link #evaluateOffline()} 驱动，
 *       实际由 {@code AlarmEvaluateJob} 周期性调度，fail-open。</li>
 * </ul>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
public interface AlarmRuleService {

    /** 评估对象维度：按应用（app 表） */
    String TARGET_TYPE_APP = "APP";

    /** 评估对象维度：按接口（api_interface 表） */
    String TARGET_TYPE_API = "API";

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
     * 查询某维度下的候选评估对象（T11 新增，供告警规则「评估对象」选择器）。
     *
     * @param targetType {@link #TARGET_TYPE_APP} 或 {@link #TARGET_TYPE_API}
     * @return 候选对象列表（id/label/extra），按 id 升序；维度非法时抛 400
     */
    List<AlarmTargetVo> targetOptions(String targetType);

    /**
     * 创建告警规则。
     *
     * @param rule 入参（ruleName/alarmType/threshold 必填；
     *             scopeType=1 时另需 targetType=APP/API）
     * @return 新建规则（含自增 ID）
     */
    AlarmRule create(AlarmRule rule);

    /**
     * 修改告警规则（部分更新语义：入参为 null 的字段保持不变）。
     *
     * <p>T11 补充：{@code targetIds} 传空串表示"清空为全部对象"（区别于不传=保持原值）；
     * scopeType 改为 2 时统一清空 targetType/targetIds。</p>
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
