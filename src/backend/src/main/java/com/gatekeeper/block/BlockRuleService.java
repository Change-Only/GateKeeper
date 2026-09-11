package com.gatekeeper.block;

import com.gatekeeper.entity.BlockRule;

import java.util.List;

/**
 * 动态封禁规则服务接口 — T04-D 风控域（RISK-CONTROL）
 *
 * <p>与存量 security_rule（检测）职责分离：本服务负责「命中后写入封禁名单」的动态规则
 * 的增删改查，以及人工封禁入口（委托 {@code BanExecutor}）。</p>
 *
 * @author GateKeeper
 * @since T04-D (APIM V2)
 */
public interface BlockRuleService {

    /**
     * 规则列表（全量，按 id 升序）。
     *
     * @return 规则列表
     */
    List<BlockRule> list();

    /**
     * 规则详情。
     *
     * @param id 规则 ID
     * @return 规则实体（不存在抛 notFound）
     */
    BlockRule getById(Long id);

    /**
     * 新建规则。enabled 默认 0，自动填充创建/更新时间。
     *
     * @param rule 规则实体（id 忽略）
     * @return 新建后的规则（含自增 id）
     */
    BlockRule create(BlockRule rule);

    /**
     * 修改规则（仅更新业务字段，保护主键与时间戳）。
     *
     * @param id   规则 ID
     * @param rule 规则实体（含待更新字段）
     */
    void update(Long id, BlockRule rule);

    /**
     * 启用/停用。enabled 为空则翻转当前状态。
     *
     * @param id      规则 ID
     * @param enabled 1=启用 0=停用（null=翻转）
     * @return 更新后的规则
     */
    BlockRule toggle(Long id, Integer enabled);

    /**
     * 人工封禁：按规则直接调用 BanExecutor（reasonCode=MANUAL）。
     *
     * @param id         规则 ID（用于取默认 ttl/原因，不存在抛 notFound）
     * @param target     封禁目标（IP 或 AppKey/AppId）
     * @param ttlSeconds 封禁时长（秒），null 则使用规则默认 ttlSeconds
     * @param reason     封禁原因，null 则使用规则描述
     */
    void manualBlock(Long id, String target, Integer ttlSeconds, String reason);
}
