package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.SysOperationLog;

/**
 * 操作审计日志服务接口 — 负责系统后台操作日志的分页查询业务
 */
public interface SysOperationLogService extends IService<SysOperationLog> {

    /**
     * 分页查询操作日志
     *
     * @param current         当前页码
     * @param size            每页条数
     * @param operationType   操作类型（可为空）
     * @param operationModule 操作模块（可为空）
     * @return 操作日志分页结果
     */
    PageResult<SysOperationLog> pageQuery(int current, int size, String operationType, String operationModule);
}
