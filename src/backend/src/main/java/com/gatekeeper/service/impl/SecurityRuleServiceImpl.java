package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.entity.SecurityRule;
import com.gatekeeper.mapper.SecurityRuleMapper;
import com.gatekeeper.service.SecurityRuleService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 安全检测规则服务实现 — 负责安全检测规则的查询、创建、更新与删除，
 * 创建时为未指定启用状态的规则默认启用。
 */
@Service
public class SecurityRuleServiceImpl extends ServiceImpl<SecurityRuleMapper, SecurityRule> implements SecurityRuleService {

    /**
     * 查询全部安全检测规则（按创建时间倒序）
     *
     * @return 规则列表
     */
    @Override
    public List<SecurityRule> listRules() {
        return baseMapper.selectList(
                new QueryWrapper<SecurityRule>().orderByDesc("created_at"));
    }

    /**
     * 创建安全检测规则
     *
     * @param rule 待创建的规则实体
     * @return 创建后的规则实体
     */
    @Override
    public SecurityRule createRule(SecurityRule rule) {
        if (rule.getEnabled() == null) {
            rule.setEnabled(true); // 未指定时默认启用
        }
        rule.setCreatedAt(LocalDateTime.now());
        rule.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(rule);
        return rule;
    }

    /**
     * 更新安全检测规则
     *
     * @param id   规则 ID
     * @param rule 待更新的规则实体
     */
    @Override
    public void updateRule(Long id, SecurityRule rule) {
        rule.setId(id);
        rule.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(rule);
    }

    /**
     * 删除安全检测规则
     *
     * @param id 规则 ID
     */
    @Override
    public void deleteRule(Long id) {
        baseMapper.deleteById(id);
    }
}
