package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.SecurityEvent;

/**
 * 安全事件服务接口 — 负责安全事件的分页查询与处置业务
 */
public interface SecurityEventService extends IService<SecurityEvent> {

    /**
     * 分页查询安全事件列表
     *
     * @param current      当前页码
     * @param size         每页条数
     * @param eventType    事件类型（可为空）
     * @param handleStatus 处理状态（可为空）
     * @return 安全事件分页结果
     */
    PageResult<SecurityEvent> pageQuery(int current, int size, String eventType, Integer handleStatus);

    /**
     * 处理安全事件
     *
     * @param id     事件 ID
     * @param status 处理后的状态值
     * @param remark 处理备注说明
     */
    void handleEvent(Long id, Integer status, String remark);
}
