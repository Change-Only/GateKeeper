package com.gatekeeper.block;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.BlockRule;
import com.gatekeeper.security.RequirePerm;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 动态封禁规则 Controller — T04-D 风控域（RISK-CONTROL）
 *
 * <p>接口路径（T04-D）：
 * <ul>
 *   <li>GET    /block-rule/list                规则列表</li>
 *   <li>GET    /block-rule/{id}                规则详情</li>
 *   <li>POST   /block-rule/create               新建规则（{@code block_rule:create} 高危）</li>
 *   <li>PUT    /block-rule/{id}/update          修改规则</li>
 *   <li>POST   /block-rule/{id}/toggle          启用/停用</li>
 *   <li>POST   /block-rule/{id}/manual-block    人工封禁（{@code block_rule:manual} 高危）</li>
 * </ul></p>
 *
 * <p>新建规则默认 enabled=0（需人工确认阈值后启用，避免误封）；人工封禁直接委托 BanExecutor。</p>
 *
 * @author GateKeeper
 * @since T04-D (APIM V2)
 */
@RestController
@RequestMapping("/block-rule")
@RequiredArgsConstructor
@Tag(name = "动态封禁规则", description = "风控域 - 动态封禁（block_rule）")
public class BlockRuleController {

    private final BlockRuleService blockRuleService;

    /**
     * 封禁规则列表（全量，按 id 升序）。
     */
    @Operation(summary = "封禁规则列表")
    @GetMapping("/list")
    public Result<List<BlockRule>> list() {
        return Result.success(blockRuleService.list());
    }

    /**
     * 封禁规则详情。
     */
    @Operation(summary = "封禁规则详情")
    @GetMapping("/{id}")
    public Result<BlockRule> detail(@PathVariable Long id) {
        return Result.success(blockRuleService.getById(id));
    }

    /**
     * 新建封禁规则（高危）。
     *
     * <p>T04-D 权限点 {@code block_rule:create}（risk=1）。</p>
     */
    @RequirePerm(value = "block_rule:create", risk = true)
    @Operation(summary = "新建封禁规则（高危）")
    @PostMapping("/create")
    public Result<BlockRule> create(@Valid @RequestBody BlockRule rule) {
        return Result.success(blockRuleService.create(rule));
    }

    /**
     * 修改封禁规则（不影响主键与时间戳）。
     *
     * <p>P0-3：篡改封禁阈值可静默削弱防护，补 {@code @RequirePerm}。库中<b>未播种</b>
     * {@code block_rule:update}，按「复用既有码、零新增」红线复用同域同风险级的
     * {@code block_rule:create}（修改规则与新建规则同属高风险的规则维护）。</p>
     */
    @RequirePerm(value = "block_rule:create", risk = true)
    @Operation(summary = "修改封禁规则")
    @PutMapping("/{id}/update")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody BlockRule rule) {
        blockRuleService.update(id, rule);
        return Result.success();
    }

    /**
     * 启用/停用封禁规则。
     *
     * <p>enabled 传 1/0 显式设置；不传则翻转当前状态。</p>
     *
     * <p>P0-3：关闭自动封禁＝削弱防护，补 {@code @RequirePerm}（复用 {@code block_rule:create}，
     * 理由同 {@link #update}）。</p>
     */
    @RequirePerm(value = "block_rule:create", risk = true)
    @Operation(summary = "启用/停用封禁规则（enable=1 / disable=0）")
    @PostMapping("/{id}/toggle")
    public Result<BlockRule> toggle(@PathVariable Long id, @RequestParam(required = false) Integer enabled) {
        return Result.success(blockRuleService.toggle(id, enabled));
    }

    /**
     * 人工封禁（高危）— 直接按规则调用 BanExecutor（reasonCode=MANUAL）。
     *
     * <p>T04-D 权限点 {@code block_rule:manual}（risk=1）。</p>
     */
    @RequirePerm(value = "block_rule:manual", risk = true)
    @Operation(summary = "人工封禁（高危）")
    @PostMapping("/{id}/manual-block")
    public Result<Void> manualBlock(@PathVariable Long id, @Valid @RequestBody ManualBlockRequest req) {
        blockRuleService.manualBlock(id, req.getTarget(), req.getTtlSeconds(), req.getReason());
        return Result.success();
    }
}
