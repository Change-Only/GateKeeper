package com.gatekeeper.block.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.mapper.BlockRuleMapper;
import com.gatekeeper.block.BlockRuleService;
import com.gatekeeper.entity.BlockRule;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.security.BanExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 动态封禁规则服务实现 — T04-D 风控域
 *
 * <p>复用 MyBatis-Plus {@link ServiceImpl} 基础能力，人工封禁委托共享组件
 * {@link BanExecutor}（与存量检测器共用封禁通道）。</p>
 *
 * @author GateKeeper
 * @since T04-D (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlockRuleServiceImpl extends ServiceImpl<BlockRuleMapper, BlockRule> implements BlockRuleService {

    private final BanExecutor banExecutor;

    @Override
    public List<BlockRule> list() {
        return baseMapper.selectList(new QueryWrapper<BlockRule>().orderByAsc("id"));
    }

    @Override
    public BlockRule getById(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("规则ID不能为空");
        }
        BlockRule rule = baseMapper.selectById(id);
        if (rule == null) {
            throw GatewayException.notFound("封禁规则不存在: id=" + id);
        }
        return rule;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BlockRule create(BlockRule rule) {
        if (rule == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        // enabled 默认 0：自动封禁规则需人工确认阈值后启用，避免误封
        rule.setEnabled(rule.getEnabled() == null ? 0 : rule.getEnabled());
        // autoBlock 默认 0（人工触发）
        rule.setAutoBlock(rule.getAutoBlock() == null ? 0 : rule.getAutoBlock());
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        rule.setId(null);
        baseMapper.insert(rule);
        log.info("BlockRule created: id={}, scope={}, reasonCode={}", rule.getId(), rule.getScope(), rule.getReasonCode());
        return rule;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BlockRule rule) {
        if (id == null) {
            throw GatewayException.badRequest("规则ID不能为空");
        }
        if (rule == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        BlockRule existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("封禁规则不存在: id=" + id);
        }
        // 仅允许更新业务字段，保护主键与系统维护的时间戳
        if (rule.getScope() != null) existing.setScope(rule.getScope());
        if (rule.getReasonCode() != null) existing.setReasonCode(rule.getReasonCode());
        if (rule.getThresholdDesc() != null) existing.setThresholdDesc(rule.getThresholdDesc());
        if (rule.getThresholdCount() != null) existing.setThresholdCount(rule.getThresholdCount());
        if (rule.getWindowMinutes() != null) existing.setWindowMinutes(rule.getWindowMinutes());
        if (rule.getTtlSeconds() != null) existing.setTtlSeconds(rule.getTtlSeconds());
        if (rule.getAutoBlock() != null) existing.setAutoBlock(rule.getAutoBlock());
        if (rule.getEnabled() != null) existing.setEnabled(rule.getEnabled());
        if (rule.getDescription() != null) existing.setDescription(rule.getDescription());
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("BlockRule updated: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BlockRule toggle(Long id, Integer enabled) {
        BlockRule existing = getById(id); // 含不存在校验
        int newEnabled = enabled != null ? enabled
                : (existing.getEnabled() != null && existing.getEnabled() == 1 ? 0 : 1);
        existing.setEnabled(newEnabled);
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("BlockRule toggled: id={}, enabled={}", id, newEnabled);
        return existing;
    }

    @Override
    public void manualBlock(Long id, String target, Integer ttlSeconds, String reason) {
        if (target == null || target.isEmpty()) {
            throw GatewayException.badRequest("封禁目标(target)不能为空");
        }
        BlockRule rule = getById(id); // 含不存在校验
        int ttl = ttlSeconds != null ? ttlSeconds
                : (rule.getTtlSeconds() != null ? rule.getTtlSeconds() : 0);
        String banReason = "MANUAL: " + (reason != null ? reason
                : (rule.getDescription() != null ? rule.getDescription() : "人工封禁"));
        banExecutor.ban(target, banReason, ttl);
        log.info("BlockRule manual-block: id={}, target={}, ttl={}", id, target, ttl);
    }
}
