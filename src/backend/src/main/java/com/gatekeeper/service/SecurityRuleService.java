package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.entity.SecurityRule;

import java.util.List;

/**
 * 安全检测规则服务接口 — 负责安全检测规则的查询、创建、更新与删除业务
 */
public interface SecurityRuleService extends IService<SecurityRule> {

    /**
     * 查询全部安全检测规则
     *
     * @return 安全检测规则列表
     */
    List<SecurityRule> listRules();

    /**
     * 创建安全检测规则
     *
     * @param rule 安全检测规则实体
     * @return 创建后的规则实体
     */
    SecurityRule createRule(SecurityRule rule);

    /**
     * 更新安全检测规则
     *
     * @param id   规则 ID
     * @param rule 待更新的规则实体
     */
    void updateRule(Long id, SecurityRule rule);

    /**
     * 删除安全检测规则
     *
     * @param id 规则 ID
     */
    void deleteRule(Long id);
}
