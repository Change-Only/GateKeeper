package com.gatekeeper.block;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.BlockRule;
import com.gatekeeper.mapper.BlockRuleMapper;
import com.gatekeeper.security.BanExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 动态封禁执行器 — T04-D 风控域核心
 *
 * <p>各安全检测器在「检测触发」时调用本执行器，按 (scope, reasonCode) 查询启用中的
 * 自动封禁规则；当本次窗口计数 {@code count >= thresholdCount} 且 {@code autoBlock=1} 时，
 * 委托共享组件 {@link BanExecutor} 完成封禁，与存量检测器的封禁通道完全一致。</p>
 *
 * <p><b>失败开放（FAIL-OPEN）</b>：规则查询/封禁过程中任何异常都被捕获并仅打日志，
 * 绝不影响网关主流程与既有检测/封禁逻辑。</p>
 *
 * @author GateKeeper
 * @since T04-D (APIM V2)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlockExecutor {

    private final BlockRuleMapper blockRuleMapper;
    private final BanExecutor banExecutor;

    /**
     * 评估并（条件）触发封禁
     *
     * @param scope      作用域（IP / APP），对应 block_rule.scope
     * @param reasonCode 触发原因码（REPLAY_ATTACK / SIGNATURE_MISMATCH / RATE_LIMIT_EXCEEDED / IP_NOT_ALLOWED / MANUAL）
     * @param target     封禁目标（IP 或 AppKey/AppId），透传给 BanExecutor
     * @param count      本次事件累计次数（窗口内）
     */
    public void evaluateAndBan(String scope, String reasonCode, String target, int count) {
        try {
            if (scope == null || reasonCode == null || target == null) {
                return;
            }
            List<BlockRule> rules = blockRuleMapper.selectList(
                    new QueryWrapper<BlockRule>().eq("scope", scope).eq("reason_code", reasonCode));
            for (BlockRule rule : rules) {
                if (rule.getEnabled() == null || rule.getEnabled() != 1) {
                    continue; // 仅处理启用中的规则
                }
                if (rule.getAutoBlock() == null || rule.getAutoBlock() != 1) {
                    continue; // 仅处理自动封禁规则
                }
                Integer threshold = rule.getThresholdCount();
                if (threshold == null || count < threshold) {
                    continue; // 未达阈值
                }
                String reason = buildReason(rule);
                // 注：BanExecutor.ban 第三参为「分钟」语义，此处透传 ttlSeconds（秒），与任务规格一致
                banExecutor.ban(target, reason, rule.getTtlSeconds() != null ? rule.getTtlSeconds() : 0);
            }
        } catch (Exception e) {
            // 失败开放：风控评估异常绝不影响网关主流程
            log.warn("BlockExecutor.evaluateAndBan failed (fail-open): scope={}, reasonCode={}, target={}, count={}, err={}",
                    scope, reasonCode, target, count, e.getMessage());
        }
    }

    /**
     * 生成封禁原因描述。
     */
    private String buildReason(BlockRule rule) {
        return "RISK_RULE[" + rule.getReasonCode() + "] rule#" + rule.getId()
                + (rule.getDescription() != null ? " " + rule.getDescription() : "");
    }
}
