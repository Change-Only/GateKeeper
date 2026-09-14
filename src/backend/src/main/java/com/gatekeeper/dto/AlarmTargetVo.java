package com.gatekeeper.dto;

import lombok.Data;

/**
 * 告警规则「评估对象」候选项 VO — T11 新增
 *
 * <p>供告警规则页「评估对象」选择器下拉使用：按维度（{@code APP}=应用 / {@code API}=接口）
 * 列出候选对象。同一结构在服务端也被复用作「规则实际生效对象」的载体
 * （见 {@code AlarmRuleServiceImpl#resolveTargets}），因此字段保持最小集：</p>
 * <ul>
 *   <li>{@code id}    —— 对象主键（app.id / api_interface.id）</li>
 *   <li>{@code label} —— 展示名（app.app_name / api_interface.interface_name）</li>
 *   <li>{@code extra} —— 次要说明（应用=描述；接口=请求方法 + 路径），可为 null</li>
 * </ul>
 *
 * @author GateKeeper
 * @since T11 (APIM V2)
 */
@Data
public class AlarmTargetVo {

    /** 对象主键（app.id / api_interface.id） */
    private Long id;

    /** 展示名（app.app_name / api_interface.interface_name） */
    private String label;

    /** 次要说明（应用=描述；接口=请求方法 + 路径），可为 null */
    private String extra;
}
