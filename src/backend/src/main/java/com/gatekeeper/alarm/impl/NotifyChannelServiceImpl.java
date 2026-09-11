package com.gatekeeper.alarm.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.alarm.NotifyChannelService;
import com.gatekeeper.alarm.NotifySender;
import com.gatekeeper.entity.NotifyChannel;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.NotifyChannelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通知渠道服务实现 — T04-C 告警域
 *
 * <p>发送动作委托 {@link NotifySender}（渠道类型决定实发/桩发）。test 接口会回写测试结果。</p>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyChannelServiceImpl extends ServiceImpl<NotifyChannelMapper, NotifyChannel>
        implements NotifyChannelService {

    private final NotifySender notifySender;

    @Override
    public List<NotifyChannel> list(Integer status) {
        QueryWrapper<NotifyChannel> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("created_at");
        return baseMapper.selectList(wrapper);
    }

    @Override
    public NotifyChannel get(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("渠道ID不能为空");
        }
        NotifyChannel channel = baseMapper.selectById(id);
        if (channel == null) {
            throw GatewayException.notFound("通知渠道不存在: id=" + id);
        }
        return channel;
    }

    @Override
    public NotifyChannel create(NotifyChannel channel) {
        if (channel == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (!StringUtils.hasText(channel.getChannelName())) {
            throw GatewayException.badRequest("渠道名称不能为空");
        }
        if (!StringUtils.hasText(channel.getChannelType())) {
            throw GatewayException.badRequest("渠道类型不能为空");
        }
        NotifyChannel entity = new NotifyChannel();
        BeanUtils.copyProperties(channel, entity);
        entity.setId(null);
        entity.setStatus(channel.getStatus() == null ? 1 : channel.getStatus());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(entity);
        log.info("NotifyChannel created: id={}, name={}, type={}",
                entity.getId(), entity.getChannelName(), entity.getChannelType());
        return entity;
    }

    @Override
    public void update(Long id, NotifyChannel channel) {
        if (id == null) {
            throw GatewayException.badRequest("渠道ID不能为空");
        }
        if (channel == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        NotifyChannel existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("通知渠道不存在: id=" + id);
        }
        if (StringUtils.hasText(channel.getChannelName())) {
            existing.setChannelName(channel.getChannelName());
        }
        if (StringUtils.hasText(channel.getChannelType())) {
            existing.setChannelType(channel.getChannelType());
        }
        if (StringUtils.hasText(channel.getChannelConfig())) {
            existing.setChannelConfig(channel.getChannelConfig());
        }
        if (channel.getStatus() != null) {
            existing.setStatus(channel.getStatus());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("NotifyChannel updated: id={}", id);
    }

    @Override
    public void delete(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("渠道ID不能为空");
        }
        NotifyChannel existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("通知渠道不存在: id=" + id);
        }
        // 引用完整性检查（T07-A）：alarm_rule.channel_ids 为逗号分隔字符串且无外键约束，
        // 被引用时拒绝删除 —— 避免留下悬空渠道 id 导致告警静默失效。
        // 选择「拒绝式」而非「级联清理」：级联会静默改写其它规则配置，在安全审计产品里不可追溯。
        long referencingRules = baseMapper.countRulesUsingChannel(id);
        if (referencingRules > 0) {
            throw GatewayException.badRequest(
                    "该通知渠道被 " + referencingRules + " 条告警规则引用，请先解除引用后再删除");
        }
        baseMapper.deleteById(id);
        log.info("NotifyChannel deleted: id={}, name={}", id, existing.getChannelName());
    }

    @Override
    public void test(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("渠道ID不能为空");
        }
        NotifyChannel channel = baseMapper.selectById(id);
        if (channel == null) {
            throw GatewayException.notFound("通知渠道不存在: id=" + id);
        }
        String name = channel.getChannelName() == null ? ("channel#" + id) : channel.getChannelName();
        String title = "[测试通知] " + name;
        String content = "这是一条测试通知，用于验证渠道「" + name + "」可达性。";
        boolean ok;
        try {
            ok = notifySender.send(channel, title, content);
        } catch (Exception e) {
            log.error("test send failed channelId={}", id, e);
            ok = false;
        }
        channel.setLastTestTime(LocalDateTime.now());
        channel.setLastTestResult(ok ? "SUCCESS" : "FAILED");
        baseMapper.updateById(channel);
    }

    @Override
    public List<NotifyChannel> listByIds(String commaIds) {
        if (!StringUtils.hasText(commaIds)) {
            return Collections.emptyList();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String s : commaIds.split(",")) {
            s = s.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                log.warn("bad channel id ignored: {}", s);
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        return baseMapper.selectBatchIds(ids);
    }
}
