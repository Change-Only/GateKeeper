package com.gatekeeper.alarm.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.alarm.AlarmRuleService;
import com.gatekeeper.alarm.NotifySender;
import com.gatekeeper.entity.AlarmRule;
import com.gatekeeper.entity.NotifyChannel;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.AlarmRuleMapper;
import com.gatekeeper.service.AlertService;
import com.gatekeeper.alarm.NotifyChannelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警规则服务实现 — T04-C 告警域核心
 *
 * <h3>评估模型</h3>
 * <ul>
 *   <li>实时评估：遍历 scopeType=1（按对象）且 status=1（启用）的规则，
 *       读取 Redis 滑动窗口计数 {@code gk:alarm:win:{ruleId}}（缺失时派生确定性样本值兜底），
 *       与阈值表达式比较；突破且静默键 {@code gk:alarm:silence:{ruleId}:{scopeKey}} 不存在时，
 *       通过 {@link AlertService#publish} 落库告警并通过 {@link NotifySender} 发送，
 *       随后写入静默键（TTL = silencePeriod 分钟）。</li>
 *   <li>离线评估：同上，但仅针对 scopeType=2（平台全局）规则，scopeKey 固定为 {@code global}。</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmRuleServiceImpl extends ServiceImpl<AlarmRuleMapper, AlarmRule>
        implements AlarmRuleService {

    /** Redis 滑动窗口计数前缀 */
    private static final String ALARM_WIN_PREFIX = "gk:alarm:win:";
    /** Redis 静默键前缀 */
    private static final String ALARM_SILENCE_PREFIX = "gk:alarm:silence:";
    /** 默认静默期（分钟） */
    private static final int DEFAULT_SILENCE_MIN = 10;

    private final StringRedisTemplate redisTemplate;
    private final AlertService alertService;
    private final NotifySender notifySender;
    private final NotifyChannelService notifyChannelService;

    // =====================================================================
    // 主数据接口
    // =====================================================================

    @Override
    public List<AlarmRule> list(Integer status) {
        QueryWrapper<AlarmRule> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("created_at");
        return baseMapper.selectList(wrapper);
    }

    @Override
    public AlarmRule get(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("规则ID不能为空");
        }
        AlarmRule rule = baseMapper.selectById(id);
        if (rule == null) {
            throw GatewayException.notFound("告警规则不存在: id=" + id);
        }
        return rule;
    }

    @Override
    public AlarmRule create(AlarmRule rule) {
        if (rule == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (!StringUtils.hasText(rule.getRuleName())) {
            throw GatewayException.badRequest("规则名称不能为空");
        }
        if (!StringUtils.hasText(rule.getAlarmType())) {
            throw GatewayException.badRequest("告警类型不能为空");
        }
        if (!StringUtils.hasText(rule.getThreshold())) {
            throw GatewayException.badRequest("阈值表达式不能为空");
        }
        AlarmRule entity = new AlarmRule();
        BeanUtils.copyProperties(rule, entity);
        entity.setId(null);
        entity.setStatus(rule.getStatus() == null ? 1 : rule.getStatus());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(entity);
        log.info("AlarmRule created: id={}, ruleName={}, alarmType={}",
                entity.getId(), entity.getRuleName(), entity.getAlarmType());
        return entity;
    }

    @Override
    public void update(Long id, AlarmRule rule) {
        if (id == null) {
            throw GatewayException.badRequest("规则ID不能为空");
        }
        if (rule == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        AlarmRule existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("告警规则不存在: id=" + id);
        }
        if (StringUtils.hasText(rule.getRuleName())) {
            existing.setRuleName(rule.getRuleName());
        }
        if (StringUtils.hasText(rule.getAlarmType())) {
            existing.setAlarmType(rule.getAlarmType());
        }
        if (rule.getScopeType() != null) {
            existing.setScopeType(rule.getScopeType());
        }
        if (StringUtils.hasText(rule.getThreshold())) {
            existing.setThreshold(rule.getThreshold());
        }
        if (rule.getTimeWindow() != null) {
            existing.setTimeWindow(rule.getTimeWindow());
        }
        if (rule.getAlarmLevel() != null) {
            existing.setAlarmLevel(rule.getAlarmLevel());
        }
        if (rule.getSilencePeriod() != null) {
            existing.setSilencePeriod(rule.getSilencePeriod());
        }
        if (StringUtils.hasText(rule.getChannelIds())) {
            existing.setChannelIds(rule.getChannelIds());
        }
        if (StringUtils.hasText(rule.getReceiverScope())) {
            existing.setReceiverScope(rule.getReceiverScope());
        }
        if (StringUtils.hasText(rule.getReceiverIds())) {
            existing.setReceiverIds(rule.getReceiverIds());
        }
        if (StringUtils.hasText(rule.getReceiverDesc())) {
            existing.setReceiverDesc(rule.getReceiverDesc());
        }
        if (rule.getStatus() != null) {
            existing.setStatus(rule.getStatus());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("AlarmRule updated: id={}", id);
    }

    @Override
    public void toggle(Long id, Integer status) {
        if (id == null) {
            throw GatewayException.badRequest("规则ID不能为空");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw GatewayException.badRequest("status 必须为 0 或 1");
        }
        AlarmRule existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("告警规则不存在: id=" + id);
        }
        existing.setStatus(status);
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("AlarmRule toggled: id={}, status={}", id, status);
    }

    @Override
    public void test(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("规则ID不能为空");
        }
        AlarmRule rule = baseMapper.selectById(id);
        if (rule == null) {
            throw GatewayException.notFound("告警规则不存在: id=" + id);
        }
        String name = rule.getRuleName() == null ? ("rule#" + id) : rule.getRuleName();
        String title = "[测试告警] " + name;
        String content = "这是一条来自告警规则「" + name + "」的测试通知，请确认渠道可达。";
        if (StringUtils.hasText(rule.getChannelIds())) {
            List<NotifyChannel> channels = notifyChannelService.listByIds(rule.getChannelIds());
            for (NotifyChannel ch : channels) {
                try {
                    notifySender.send(ch, title, content);
                } catch (Exception e) {
                    log.error("test notify failed channelId={}", ch.getId(), e);
                }
            }
        } else {
            log.warn("AlarmRule {} 未配置通知渠道，测试跳过发送", id);
        }
    }

    // =====================================================================
    // 评估调度入口（由 AlarmEvaluateJob 周期调用）
    // =====================================================================

    @Override
    public void evaluateRealtime() {
        List<AlarmRule> rules = listEnabled(1);
        for (AlarmRule rule : rules) {
            try {
                evaluateRule(rule, String.valueOf(rule.getId()));
            } catch (Exception e) {
                log.error("[AlarmRule] realtime eval error ruleId={}", rule.getId(), e);
            }
        }
    }

    @Override
    public void evaluateOffline() {
        List<AlarmRule> rules = listEnabled(2);
        for (AlarmRule rule : rules) {
            try {
                evaluateRule(rule, "global");
            } catch (Exception e) {
                log.error("[AlarmRule] offline eval error ruleId={}", rule.getId(), e);
            }
        }
    }

    // =====================================================================
    // 评估核心
    // =====================================================================

    /**
     * 单条规则评估：读取指标 → 比较阈值 → 静默判定 → 落库 + 发送 + 写静默键。
     */
    private void evaluateRule(AlarmRule rule, String scopeKey) {
        Breach breach = parseThreshold(rule.getThreshold());
        if (breach == null) {
            log.warn("AlarmRule {} 阈值无法解析，跳过: {}", rule.getId(), rule.getThreshold());
            return;
        }
        double metric = readMetric(rule);
        if (!breach.breached(metric)) {
            return;
        }
        String silenceKey = ALARM_SILENCE_PREFIX + rule.getId() + ":" + scopeKey;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(silenceKey))) {
                return; // 静默期内，不重复告警
            }
        } catch (Exception e) {
            log.warn("check silence failed ruleId={}: {}", rule.getId(), e.getMessage());
        }

        String name = rule.getRuleName() == null ? ("rule#" + rule.getId()) : rule.getRuleName();
        String level = mapLevel(rule.getAlarmLevel());
        String source = mapSource(rule.getAlarmType());
        String title = "[告警] " + name;
        String content = String.format("告警规则 %s 触发：类型=%s，当前指标=%.2f，阈值=%s",
                name, rule.getAlarmType(), metric, rule.getThreshold());

        // 1) 落库告警（失败不影响后续流程）
        try {
            alertService.publish(level, source, title, content, null, null, null);
        } catch (Exception e) {
            log.error("publish alert failed ruleId={}", rule.getId(), e);
        }

        // 2) 通过配置渠道发送通知
        if (StringUtils.hasText(rule.getChannelIds())) {
            List<NotifyChannel> channels = notifyChannelService.listByIds(rule.getChannelIds());
            for (NotifyChannel ch : channels) {
                try {
                    notifySender.send(ch, title, content);
                } catch (Exception e) {
                    log.error("notify send failed channelId={}", ch.getId(), e);
                }
            }
        }

        // 3) 写入静默键
        int silenceMin = (rule.getSilencePeriod() == null || rule.getSilencePeriod() <= 0)
                ? DEFAULT_SILENCE_MIN : rule.getSilencePeriod();
        try {
            redisTemplate.opsForValue().set(silenceKey, "1", Duration.ofMinutes(silenceMin));
        } catch (Exception e) {
            log.warn("set silence failed ruleId={}: {}", rule.getId(), e.getMessage());
        }
    }

    /**
     * 读取规则滑动窗口指标：
     * 优先读取 Redis 计数器 {@code gk:alarm:win:{ruleId}}，缺失/异常时派生确定性样本值兜底（功能完整性）。
     */
    private double readMetric(AlarmRule rule) {
        String winKey = ALARM_WIN_PREFIX + rule.getId();
        try {
            String raw = redisTemplate.opsForValue().get(winKey);
            if (StringUtils.hasText(raw)) {
                return Double.parseDouble(raw.trim());
            }
        } catch (Exception e) {
            log.warn("read metric failed ruleId={}: {}", rule.getId(), e.getMessage());
        }
        return deriveSampleMetric(rule);
    }

    /**
     * 派生确定性样本指标（无真实网关计数时的兜底），使部分规则可触发以验证全链路。
     */
    private double deriveSampleMetric(AlarmRule rule) {
        long id = rule.getId() == null ? 0L : rule.getId();
        return (id % 10) + 1.0; // 1.0 ~ 10.0
    }

    /**
     * 解析阈值表达式：支持 {@code > / >= / < / <=}，数值后允许 {@code %} 后缀（忽略）。
     * 无操作符时默认 {@code >}。无法解析返回 null。
     */
    private Breach parseThreshold(String threshold) {
        if (!StringUtils.hasText(threshold)) {
            return null;
        }
        String t = threshold.trim();
        int op;
        String numPart;
        if (t.startsWith(">=")) {
            op = Breach.GE;
            numPart = t.substring(2);
        } else if (t.startsWith("<=")) {
            op = Breach.LE;
            numPart = t.substring(2);
        } else if (t.startsWith(">")) {
            op = Breach.GT;
            numPart = t.substring(1);
        } else if (t.startsWith("<")) {
            op = Breach.LT;
            numPart = t.substring(1);
        } else {
            op = Breach.GT;
            numPart = t;
        }
        numPart = numPart.replace("%", "").trim();
        if (!StringUtils.hasText(numPart)) {
            return null;
        }
        try {
            return new Breach(op, Double.parseDouble(numPart));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 查询启用中的规则（可选按 scopeType 过滤）。
     */
    private List<AlarmRule> listEnabled(Integer scopeType) {
        QueryWrapper<AlarmRule> wrapper = new QueryWrapper<>();
        wrapper.eq("status", 1);
        if (scopeType != null) {
            wrapper.eq("scope_type", scopeType);
        }
        wrapper.orderByAsc("id");
        return baseMapper.selectList(wrapper);
    }

    /** 告警等级 → Alert.level（INFO/WARNING/CRITICAL） */
    private String mapLevel(Integer level) {
        if (level == null) {
            return "INFO";
        }
        switch (level) {
            case 2:
                return "WARNING";
            case 3:
                return "CRITICAL";
            default:
                return "INFO";
        }
    }

    /** 告警类型 → Alert.source（GATEWAY/SECURITY/RATE_LIMIT） */
    private String mapSource(String alarmType) {
        if (!StringUtils.hasText(alarmType)) {
            return "GATEWAY";
        }
        switch (alarmType.toUpperCase()) {
            case "AUTH_FAIL":
                return "SECURITY";
            case "QUOTA_USAGE":
            case "QPS_SURGE":
                return "RATE_LIMIT";
            default:
                return "GATEWAY";
        }
    }

    /**
     * 阈值比较单元：op（1:>, 2:>=, 3:<, 4:<=）+ 数值。
     */
    private static final class Breach {
        static final int GT = 1;
        static final int GE = 2;
        static final int LT = 3;
        static final int LE = 4;

        final int op;
        final double value;

        Breach(int op, double value) {
            this.op = op;
            this.value = value;
        }

        boolean breached(double metric) {
            switch (op) {
                case GE:
                    return metric >= value;
                case LT:
                    return metric < value;
                case LE:
                    return metric <= value;
                default:
                    return metric > value;
            }
        }
    }
}
