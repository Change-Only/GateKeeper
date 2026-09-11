package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.Alert;
import com.gatekeeper.mapper.AlertMapper;
import com.gatekeeper.service.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 告警服务实现 — 负责运营告警的发布、查询、未读统计与处置。
 *
 * <p>发布方法对落库异常做了防御，确保告警写入失败不影响网关主流程；
 * 其余查询/处置方法复用 MyBatis-Plus 的 {@code baseMapper} 完成。</p>
 */
@Slf4j
@Service
public class AlertServiceImpl extends ServiceImpl<AlertMapper, Alert> implements AlertService {

    /**
     * 发布一条告警：组装实体并落库，异常时仅记日志不影响调用方
     */
    @Override
    public void publish(String level, String source, String title, String content,
                        Long relatedAppId, String relatedAppName, String relatedIp) {
        try {
            Alert alert = new Alert();
            alert.setTitle(title);
            alert.setLevel(level);
            alert.setSource(source);
            alert.setContent(content);
            alert.setRelatedAppId(relatedAppId);
            alert.setRelatedAppName(relatedAppName);
            alert.setRelatedIp(relatedIp);
            alert.setStatus(STATUS_UNREAD);
            LocalDateTime now = LocalDateTime.now();
            alert.setOccurredAt(now);
            alert.setCreatedAt(now);
            baseMapper.insert(alert);
        } catch (Exception e) {
            // 告警落库失败不影响业务主流程，仅记录错误日志
            log.error("Failed to publish alert: level={}, source={}, title={}", level, source, title, e);
        }
    }

    /**
     * 分页查询告警，支持按等级、来源、状态过滤，按发生时间倒序
     */
    @Override
    public PageResult<Alert> pageQuery(int current, int size, String level, String source, Integer status) {
        Page<Alert> page = new Page<>(current, size);
        QueryWrapper<Alert> wrapper = new QueryWrapper<>();
        if (level != null && !level.isEmpty()) {
            wrapper.eq("level", level);
        }
        if (source != null && !source.isEmpty()) {
            wrapper.eq("source", source);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("occurred_at");
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 统计未读告警（status = 0）
     */
    @Override
    public long unreadCount() {
        QueryWrapper<Alert> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_UNREAD);
        return baseMapper.selectCount(wrapper);
    }

    /**
     * 标记单条告警为已读（仅当仍为未读时更新，写入已读时间）
     */
    @Override
    public void markRead(Long id) {
        Alert alert = baseMapper.selectById(id);
        if (alert == null || alert.getStatus() == null || alert.getStatus() != STATUS_UNREAD) {
            return;
        }
        alert.setStatus(STATUS_READ);
        alert.setReadAt(LocalDateTime.now());
        baseMapper.updateById(alert);
    }

    /**
     * 一键全部标记已读：仅更新未读记录，批量写入已读时间
     */
    @Override
    public void markAllRead() {
        UpdateWrapper<Alert> wrapper = new UpdateWrapper<>();
        wrapper.eq("status", STATUS_UNREAD);
        Alert update = new Alert();
        update.setStatus(STATUS_READ);
        update.setReadAt(LocalDateTime.now());
        baseMapper.update(update, wrapper);
    }

    /**
     * 处置告警：更新为已处理/已忽略，写入备注与处理时间
     */
    @Override
    public void handleAlert(Long id, Integer status, String remark) {
        Alert alert = new Alert();
        alert.setId(id);
        alert.setStatus(status);
        alert.setHandleRemark(remark);
        alert.setHandledAt(LocalDateTime.now());
        baseMapper.updateById(alert);
    }
}
