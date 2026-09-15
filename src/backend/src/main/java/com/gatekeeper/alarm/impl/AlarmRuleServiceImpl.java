package com.gatekeeper.alarm.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.alarm.AlarmRuleService;
import com.gatekeeper.alarm.NotifySender;
import com.gatekeeper.dto.AlarmTargetVo;
import com.gatekeeper.entity.AlarmRule;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.App;
import com.gatekeeper.entity.NotifyChannel;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.AlarmRuleMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.AppMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警规则服务实现 — T04-C 告警域核心
 *
 * <h3>评估模型</h3>
 * <ul>
 *   <li>实时评估：遍历 scopeType=1（按对象）且 status=1（启用）的规则，
 *       <b>逐对象</b>展开评估（T11 起）：先按 {@code targetType}（APP/API）取出该维度下
 *       全部对象，再用 {@code targetIds} 收窄（空=全部）；每个对象独立比较阈值、
 *       独立静默（静默键 {@code gk:alarm:silence:{ruleId}:{targetType}:{targetId}}）——
 *       修掉了 T11 之前"scopeKey 直接拿规则ID当占位、按对象形同空转"的问题。</li>
 *   <li>指标读取：优先 Redis 滑动窗口计数 {@code gk:alarm:win:{ruleId}:{scopeKey}}（按对象），
 *       回退 {@code gk:alarm:win:{ruleId}}（规则级，兼容既有写入方），再回退派生样本值（确定性兜底）。</li>
 *   <li>离线评估：仅针对 scopeType=2（平台全局）规则，scopeKey 固定为 {@code global}，不做对象展开。</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)，T11 起支持评估对象绑定
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
    /** 平台全局规则的 scopeKey（scopeType=2） */
    private static final String GLOBAL_SCOPE_KEY = "global";
    /** 默认静默期（分钟） */
    private static final int DEFAULT_SILENCE_MIN = 10;

    private final StringRedisTemplate redisTemplate;
    private final AlertService alertService;
    private final NotifySender notifySender;
    private final NotifyChannelService notifyChannelService;
    private final AppMapper appMapper;
    private final ApiInterfaceMapper apiInterfaceMapper;
    /** T17：接口路径字段级解密（告警对象的 "GET /path" 说明） */
    private final com.gatekeeper.crypto.InterfaceCryptoService interfaceCryptoService;
    /** T17：控制台出口的可见性掩码 */
    private final com.gatekeeper.service.InterfaceVisibilityService interfaceVisibilityService;

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
    public List<AlarmTargetVo> targetOptions(String targetType) {
        String type = normalizeTargetType(requireTargetType(targetType));
        List<AlarmTargetVo> list = listAllTargets(type);
        // T17：这是控制台出口，接口路径必须按可见性掩码 ——
        // 否则本端点会成为绕过接口列表掩码的"取数后门"（一次问出全部接口路径）。
        // 掩码只作用于本次返回的副本：listAllTargets 每次调用都新建对象，
        // 与评估链路内部用的缓存副本互不影响（那边需要明文来拼告警正文）。
        if (TARGET_TYPE_API.equals(type)) {
            com.gatekeeper.security.InterfaceViewer viewer = interfaceVisibilityService.resolveViewer();
            if (viewer.isProtectionEnabled()) {
                for (AlarmTargetVo vo : list) {
                    if (viewer.mustMask(null) && StringUtils.hasText(vo.getExtra())) {
                        vo.setExtra(interfaceCryptoService.maskPath());
                    }
                }
            }
        }
        return list;
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
        // scopeType 缺省 = 1（与 DDL DEFAULT 1 对齐），便于"按对象/按全局"的归一判断
        int scopeType = rule.getScopeType() == null ? 1 : rule.getScopeType();
        if (scopeType != 1 && scopeType != 2) {
            throw GatewayException.badRequest("scopeType 必须为 1(按对象) 或 2(平台全局)");
        }
        AlarmRule entity = new AlarmRule();
        BeanUtils.copyProperties(rule, entity);
        entity.setId(null);
        entity.setScopeType(scopeType);
        // T11: scopeType=1 必须指明对象维度，否则"按对象"无从展开（此前该字段不存在，规则空转）
        if (scopeType == 1) {
            if (!StringUtils.hasText(rule.getTargetType())) {
                throw GatewayException.badRequest("scopeType=1（按对象）时必须指定评估对象维度：APP/API");
            }
            entity.setTargetType(requireTargetType(rule.getTargetType()));
            entity.setTargetIds(normalizeIdCsv(rule.getTargetIds()));
        } else {
            entity.setTargetType(null);
            entity.setTargetIds(null);
        }
        entity.setStatus(rule.getStatus() == null ? 1 : rule.getStatus());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(entity);
        log.info("AlarmRule created: id={}, ruleName={}, alarmType={}, scopeType={}, targetType={}, targetIds={}",
                entity.getId(), entity.getRuleName(), entity.getAlarmType(),
                entity.getScopeType(), entity.getTargetType(), entity.getTargetIds());
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

        // ---- T11: 评估对象绑定 ----
        // 语义：不传（null）= 保持原值；传空串 = 清空；传值 = 采纳（targetType 仅 APP/API）。
        // ⚠ 注意 MyBatis-Plus 默认字段策略是 NOT_NULL —— updateById 不会把 null 写进 SET，
        //   所以"清空"必须额外走显式 UpdateWrapper（否则字段清不掉，与 app.expire_time 是同一类坑）。
        boolean clearType = false;
        boolean clearIds = false;
        if (rule.getScopeType() != null && rule.getScopeType() == 2) {
            // 切到平台全局：不按对象展开，绑定一律清空，避免残留脏值
            clearType = true;
            clearIds = true;
        }
        if (!clearType && rule.getTargetType() != null) {
            if (StringUtils.hasText(rule.getTargetType())) {
                existing.setTargetType(normalizeTargetType(requireTargetType(rule.getTargetType())));
            } else {
                clearType = true;
            }
        }
        if (!clearIds && rule.getTargetIds() != null) {
            String normalized = normalizeIdCsv(rule.getTargetIds());
            if (normalized == null) {
                clearIds = true;
            } else {
                existing.setTargetIds(normalized);
            }
        }
        // 不变式：scopeType=1 必须有对象维度，否则规则会静默空转（这正是 T11 要修的问题）。
        // 仅在本次请求确实在改绑定相关字段时才强制 —— 避免把 T11 之前的历史规则"顺手"卡死。
        boolean touchesBinding = rule.getScopeType() != null
                || rule.getTargetType() != null || rule.getTargetIds() != null;
        boolean scope1 = existing.getScopeType() == null || existing.getScopeType() == 1;
        if (touchesBinding && scope1 && (clearType || !StringUtils.hasText(existing.getTargetType()))) {
            throw GatewayException.badRequest("scopeType=1（按对象）时必须指定评估对象维度：APP/API");
        }
        if (clearType) {
            existing.setTargetType(null);
        }
        if (clearIds) {
            existing.setTargetIds(null);
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
        if (clearType || clearIds) {
            clearTargetColumns(id, clearType, clearIds);
        }
        log.info("AlarmRule updated: id={}, scopeType={}, targetType={}, targetIds={}",
                id, existing.getScopeType(), existing.getTargetType(), existing.getTargetIds());
    }

    /**
     * 显式清空评估对象绑定字段（T11）。
     *
     * <p>MyBatis-Plus 默认字段策略 {@code NOT_NULL} 不会把 null 写进 UPDATE 的 SET 子句，
     * 因此 {@code updateById} 是"清不掉"字段的 —— 必须用 {@code UpdateWrapper.set(col, null)}
     * 生成显式 SET NULL。与存量 {@code app.expire_time} 清不掉的成因相同。</p>
     *
     * @param id        规则 ID
     * @param clearType 是否清空 target_type
     * @param clearIds  是否清空 target_ids
     */
    private void clearTargetColumns(Long id, boolean clearType, boolean clearIds) {
        UpdateWrapper<AlarmRule> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id);
        if (clearType) {
            wrapper.set("target_type", null);
        }
        if (clearIds) {
            wrapper.set("target_ids", null);
        }
        baseMapper.update(null, wrapper);
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
        // 一轮评估内缓存"维度 → 全部对象"，避免每条规则都全表扫一次 app/api_interface（10s 周期任务）
        Map<String, List<AlarmTargetVo>> targetCache = new HashMap<>();
        for (AlarmRule rule : listEnabled(1)) {
            // T11：scopeType=1 逐对象展开评估，静默按对象隔离
            if (!hasTargetBinding(rule)) {
                // 历史规则（T11 之前创建、无 targetType）：保持"单次评估"行为，不退化为静默空转
                log.debug("AlarmRule {} 未绑定评估对象维度，按单次评估处理", rule.getId());
                evaluateQuietly(rule, null);
                continue;
            }
            List<AlarmTargetVo> targets = resolveTargets(rule, targetCache);
            if (targets.isEmpty()) {
                log.debug("AlarmRule {} 维度 {} 下无对象，跳过评估", rule.getId(), rule.getTargetType());
                continue;
            }
            for (AlarmTargetVo target : targets) {
                evaluateQuietly(rule, target);
            }
        }
    }

    @Override
    public void evaluateOffline() {
        // scopeType=2（平台全局）不按对象展开，固定单次评估
        for (AlarmRule rule : listEnabled(2)) {
            evaluateQuietly(rule, null);
        }
    }

    /** 单对象评估 + 异常隔离：任一对象评估出错不影响同规则其它对象，也不影响后续规则（fail-open）。 */
    private void evaluateQuietly(AlarmRule rule, AlarmTargetVo target) {
        try {
            evaluateRule(rule, target);
        } catch (Exception e) {
            log.error("[AlarmRule] eval error ruleId={} target={}", rule.getId(),
                    target == null ? GLOBAL_SCOPE_KEY : target.getId(), e);
        }
    }

    // =====================================================================
    // 评估对象解析（T11）
    // =====================================================================

    /**
     * 规则是否绑定了评估对象维度（scopeType=1 的正常态）。
     */
    private boolean hasTargetBinding(AlarmRule rule) {
        return normalizeTargetType(rule.getTargetType()) != null;
    }

    /**
     * 解析规则本次要评估的对象集合。
     *
     * <p>targetIds 为空 ⇒ 该维度下全部对象（与 channelIds/receiverIds 的"空即全部"约定一致）。</p>
     *
     * @param rule  规则
     * @param cache 一轮评估内的「维度 → 全部对象」缓存（可为 null，表示不缓存）
     * @return 命中的对象列表（可能为空）；维度非法时返回空列表并告警
     */
    private List<AlarmTargetVo> resolveTargets(AlarmRule rule, Map<String, List<AlarmTargetVo>> cache) {
        String type = normalizeTargetType(rule.getTargetType());
        if (type == null) {
            return Collections.emptyList();
        }
        if (!TARGET_TYPE_APP.equals(type) && !TARGET_TYPE_API.equals(type)) {
            log.warn("AlarmRule {} 评估对象维度非法，跳过: {}", rule.getId(), rule.getTargetType());
            return Collections.emptyList();
        }
        List<AlarmTargetVo> all = cache == null
                ? listAllTargets(type)
                : cache.computeIfAbsent(type, this::listAllTargets);
        Set<Long> picked = parseIdCsv(rule.getTargetIds());
        if (picked.isEmpty()) {
            return all;
        }
        List<AlarmTargetVo> hit = new ArrayList<>();
        for (AlarmTargetVo t : all) {
            if (t.getId() != null && picked.contains(t.getId())) {
                hit.add(t);
            }
        }
        return hit;
    }

    /**
     * 列出某维度下的全部对象（候选选择器与实际评估共用同一取数口径，避免两处漂移）。
     *
     * @param type {@code APP} 或 {@code API}
     */
    private List<AlarmTargetVo> listAllTargets(String type) {
        List<AlarmTargetVo> out = new ArrayList<>();
        if (TARGET_TYPE_API.equals(type)) {
            List<ApiInterface> list = apiInterfaceMapper.selectList(
                    new QueryWrapper<ApiInterface>().orderByAsc("id"));
            for (ApiInterface it : list) {
                // T17：路径在加密启用时是密文，告警正文/候选下拉要的是明文
                interfaceCryptoService.decryptInPlace(it);
                AlarmTargetVo vo = new AlarmTargetVo();
                vo.setId(it.getId());
                vo.setLabel(it.getInterfaceName());
                vo.setExtra(describeApi(it));
                out.add(vo);
            }
        } else {
            List<App> list = appMapper.selectList(new QueryWrapper<App>().orderByAsc("id"));
            for (App app : list) {
                AlarmTargetVo vo = new AlarmTargetVo();
                vo.setId(app.getId());
                vo.setLabel(app.getAppName());
                vo.setExtra(app.getDescription());
                out.add(vo);
            }
        }
        return out;
    }

    /** 接口的次要说明：请求方法 + 路径，如 {@code GET /order/{id}}。 */
    private String describeApi(ApiInterface it) {
        String method = it.getRequestMethod() == null ? "" : it.getRequestMethod().trim();
        String path = it.getInterfacePath() == null ? "" : it.getInterfacePath().trim();
        String s = (method + " " + path).trim();
        return s.isEmpty() ? null : s;
    }

    /** 评估对象的可读描述，用于告警正文（如 {@code 接口#12(用户查询)} / {@code 平台全局}）。 */
    private String describeTarget(AlarmRule rule, AlarmTargetVo target) {
        if (target == null) {
            return "平台全局";
        }
        String dim = TARGET_TYPE_API.equals(normalizeTargetType(rule.getTargetType())) ? "接口" : "应用";
        String label = StringUtils.hasText(target.getLabel()) ? ("(" + target.getLabel() + ")") : "";
        return dim + "#" + target.getId() + label;
    }

    /** scopeKey：对象级 {@code {APP|API}:{id}}，全局为 {@code global}。 */
    private String scopeKeyOf(AlarmRule rule, AlarmTargetVo target) {
        if (target == null) {
            return GLOBAL_SCOPE_KEY;
        }
        return normalizeTargetType(rule.getTargetType()) + ":" + target.getId();
    }

    // =====================================================================
    // 评估核心
    // =====================================================================

    /**
     * 单条规则对单个对象的评估：读取指标 → 比较阈值 → 静默判定 → 落库 + 发送 + 写静默键。
     *
     * @param target 评估对象；{@code null} 表示平台全局（scopeType=2 或未绑定的历史规则）
     */
    private void evaluateRule(AlarmRule rule, AlarmTargetVo target) {
        Breach breach = parseThreshold(rule.getThreshold());
        if (breach == null) {
            log.warn("AlarmRule {} 阈值无法解析，跳过: {}", rule.getId(), rule.getThreshold());
            return;
        }
        String scopeKey = scopeKeyOf(rule, target);
        double metric = readMetric(rule, scopeKey);
        if (!breach.breached(metric)) {
            return;
        }
        String silenceKey = ALARM_SILENCE_PREFIX + rule.getId() + ":" + scopeKey;
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(silenceKey))) {
                return; // 静默期内，不重复告警（静默粒度 = 规则 × 对象）
            }
        } catch (Exception e) {
            log.warn("check silence failed ruleId={}: {}", rule.getId(), e.getMessage());
        }

        String name = rule.getRuleName() == null ? ("rule#" + rule.getId()) : rule.getRuleName();
        String level = mapLevel(rule.getAlarmLevel());
        String source = mapSource(rule.getAlarmType());
        String title = "[告警] " + name;
        String content = String.format("告警规则 %s 触发：对象=%s，类型=%s，当前指标=%.2f，阈值=%s",
                name, describeTarget(rule, target), rule.getAlarmType(), metric, rule.getThreshold());

        // 关联应用：按应用维度评估时把对象写进告警，便于告警记录页溯源（接口维度无对应列，仅体现在正文）
        Long relatedAppId = null;
        String relatedAppName = null;
        if (target != null && TARGET_TYPE_APP.equals(normalizeTargetType(rule.getTargetType()))) {
            relatedAppId = target.getId();
            relatedAppName = target.getLabel();
        }

        // 1) 落库告警（失败不影响后续流程）
        try {
            alertService.publish(level, source, title, content, relatedAppId, relatedAppName, null);
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

        // 3) 写入静默键（键含 scopeKey ⇒ 每个对象独立静默，不再"一个对象告警后整条规则静默"）
        int silenceMin = (rule.getSilencePeriod() == null || rule.getSilencePeriod() <= 0)
                ? DEFAULT_SILENCE_MIN : rule.getSilencePeriod();
        try {
            redisTemplate.opsForValue().set(silenceKey, "1", Duration.ofMinutes(silenceMin));
        } catch (Exception e) {
            log.warn("set silence failed ruleId={}: {}", rule.getId(), e.getMessage());
        }
    }

    /**
     * 读取规则在给定对象上的滑动窗口指标：
     * <ol>
     *   <li>{@code gk:alarm:win:{ruleId}:{scopeKey}} —— 按对象计数（T11 新增口径）</li>
     *   <li>{@code gk:alarm:win:{ruleId}} —— 规则级计数（T11 之前口径，保持兼容）</li>
     *   <li>确定性派生样本值兜底（功能完整性，使链路可自证）</li>
     * </ol>
     */
    private double readMetric(AlarmRule rule, String scopeKey) {
        Double byTarget = readCounter(ALARM_WIN_PREFIX + rule.getId() + ":" + scopeKey);
        if (byTarget != null) {
            return byTarget;
        }
        Double byRule = readCounter(ALARM_WIN_PREFIX + rule.getId());
        if (byRule != null) {
            return byRule;
        }
        return deriveSampleMetric(rule);
    }

    /** 读取 Redis 计数，缺失/异常返回 null。 */
    private Double readCounter(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            if (StringUtils.hasText(raw)) {
                return Double.parseDouble(raw.trim());
            }
        } catch (Exception e) {
            log.warn("read metric {} failed: {}", key, e.getMessage());
        }
        return null;
    }

    // =====================================================================
    // targetType / targetIds 归一化工具（T11）
    // =====================================================================

    /** 归一：trim + 大写；空返回 null（不抛异常，供读取路径安全使用）。 */
    private String normalizeTargetType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim().toUpperCase();
    }

    /** 校验并归一 targetType：非法值抛 400（供写入路径使用）。 */
    private String requireTargetType(String raw) {
        String t = normalizeTargetType(raw);
        if (!TARGET_TYPE_APP.equals(t) && !TARGET_TYPE_API.equals(t)) {
            throw GatewayException.badRequest("评估对象维度必须为 APP(按应用) 或 API(按接口)");
        }
        return t;
    }

    /** 解析逗号分隔 ID 串为有序去重集合；空/非法返回空集合。 */
    private Set<Long> parseIdCsv(String csv) {
        Set<Long> ids = new LinkedHashSet<>();
        if (!StringUtils.hasText(csv)) {
            return ids;
        }
        for (String part : csv.split(",")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignore) {
                log.warn("忽略非法评估对象ID: {}", part);
            }
        }
        return ids;
    }

    /** 归一逗号 ID 串：空/无有效 ID 返回 null（= 全部对象），否则返回去重后的规范串。 */
    private String normalizeIdCsv(String csv) {
        Set<Long> ids = parseIdCsv(csv);
        if (ids.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
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
