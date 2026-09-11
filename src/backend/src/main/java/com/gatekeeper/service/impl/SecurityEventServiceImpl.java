package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.SecurityEvent;
import com.gatekeeper.mapper.SecurityEventMapper;
import com.gatekeeper.service.SecurityEventService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 安全事件服务实现 — 负责安全事件的分页查询与处置，
 * 支持按事件类型与处置状态筛选，并记录处置结果、备注与处置时间。
 */
@Service
public class SecurityEventServiceImpl extends ServiceImpl<SecurityEventMapper, SecurityEvent> implements SecurityEventService {

    /**
     * 分页查询安全事件列表，支持按事件类型与处置状态筛选
     *
     * @param current      当前页码
     * @param size         每页条数
     * @param eventType    事件类型（精确匹配，可为空）
     * @param handleStatus 处置状态（可为空，为空则不筛选）
     * @return 安全事件分页结果
     */
    @Override
    public PageResult<SecurityEvent> pageQuery(int current, int size, String eventType, Integer handleStatus) {
        Page<SecurityEvent> page = new Page<>(current, size);
        QueryWrapper<SecurityEvent> wrapper = new QueryWrapper<>();
        if (eventType != null && !eventType.isEmpty()) {
            wrapper.eq("event_type", eventType); // 按事件类型过滤
        }
        if (handleStatus != null) {
            wrapper.eq("handle_status", handleStatus); // 按处置状态过滤
        }
        wrapper.orderByDesc("occurred_at"); // 按发生时间倒序
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 处置安全事件：更新处置状态、备注与处置时间
     *
     * @param id     事件 ID
     * @param status 处置状态
     * @param remark 处置备注
     */
    @Override
    public void handleEvent(Long id, Integer status, String remark) {
        SecurityEvent event = new SecurityEvent();
        event.setId(id);
        event.setHandleStatus(status);
        event.setHandleRemark(remark);
        event.setHandledAt(LocalDateTime.now()); // 记录处置时间
        baseMapper.updateById(event);
    }
}
