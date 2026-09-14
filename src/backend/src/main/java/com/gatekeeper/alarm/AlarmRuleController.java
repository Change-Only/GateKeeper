package com.gatekeeper.alarm;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.AlarmTargetVo;
import com.gatekeeper.entity.AlarmRule;
import com.gatekeeper.security.RequirePerm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 告警规则管理 Controller — T04-C 告警域（规则 + 渠道 + 评估 + 通知）
 *
 * <p>接口路径：
 * <ul>
 *   <li>GET    /alarm-rule/list                      按 status 筛选规则列表</li>
 *   <li>GET    /alarm-rule/target-options            评估对象候选（T11 新增：APP=应用 / API=接口）</li>
 *   <li>GET    /alarm-rule/{id}                      规则详情</li>
 *   <li>POST   /alarm-rule/create                    创建规则（{@code alarm_rule:create} 高危）</li>
 *   <li>PUT    /alarm-rule/{id}/update               修改规则</li>
 *   <li>POST   /alarm-rule/{id}/toggle               启用/停用（status=1/0）</li>
 *   <li>POST   /alarm-rule/{id}/test                 通过已配置渠道发送测试告警</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
@RestController
@RequestMapping("/alarm-rule")
@RequiredArgsConstructor
@Tag(name = "告警规则", description = "告警规则管理")
public class AlarmRuleController {

    private final AlarmRuleService alarmRuleService;

    /**
     * 查询告警规则列表（可选按 status 过滤）。
     */
    @Operation(summary = "查询告警规则列表")
    @GetMapping("/list")
    public Result<List<AlarmRule>> list(@RequestParam(required = false) Integer status) {
        return Result.success(alarmRuleService.list(status));
    }

    /**
     * 查询某维度下的候选评估对象（T11 新增，供「评估对象」选择器）。
     *
     * <p>只读且仅返回 id/名称/次要说明，与 list 同策略**不加**权限点
     * （T07-A 对告警规则 list/detail/update/toggle 的"不加权限点"裁定保持一致）。</p>
     *
     * @param targetType {@code APP}=按应用 / {@code API}=按接口
     */
    @Operation(summary = "查询评估对象候选（APP=应用 / API=接口）")
    @GetMapping("/target-options")
    public Result<List<AlarmTargetVo>> targetOptions(@RequestParam String targetType) {
        return Result.success(alarmRuleService.targetOptions(targetType));
    }

    /**
     * 告警规则详情。
     */
    @Operation(summary = "告警规则详情")
    @GetMapping("/{id}")
    public Result<AlarmRule> detail(@PathVariable Long id) {
        return Result.success(alarmRuleService.get(id));
    }

    /**
     * 创建告警规则（高危）。
     *
     * <p>T04-C 权限点 {@code alarm_rule:create}（risk=1）。</p>
     */
    @RequirePerm(value = "alarm_rule:create", risk = true)
    @Operation(summary = "创建告警规则（高危）")
    @PostMapping("/create")
    public Result<AlarmRule> create(@RequestBody AlarmRule dto) {
        return Result.success(alarmRuleService.create(dto));
    }

    /**
     * 修改告警规则（名称/阈值/窗口/等级/渠道/接收人等）。
     */
    @Operation(summary = "修改告警规则")
    @PutMapping("/{id}/update")
    public Result<Void> update(@PathVariable Long id, @RequestBody AlarmRule dto) {
        alarmRuleService.update(id, dto);
        return Result.success();
    }

    /**
     * 启用/停用告警规则。
     *
     * @param status 1=启用, 0=停用
     */
    @Operation(summary = "启用/停用告警规则")
    @PostMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam Integer status) {
        alarmRuleService.toggle(id, status);
        return Result.success();
    }

    /**
     * 发送测试告警（通过规则已配置的渠道）。
     *
     * <p>存在外发副作用，T07-A 补权限点 {@code alarm_rule:test}（risk=1），
     * 与 T05 裁定的 {@code api_env_config:test} 属同一类面。
     * list / detail / update / toggle 按 T07-A 策略**不加**权限点。</p>
     */
    @RequirePerm(value = "alarm_rule:test", risk = true)
    @Operation(summary = "发送测试告警（高危，外发）")
    @PostMapping("/{id}/test")
    public Result<Void> test(@PathVariable Long id) {
        alarmRuleService.test(id);
        return Result.success();
    }
}
