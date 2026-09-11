package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.SysOperationLog;
import com.gatekeeper.mapper.SysOperationLogMapper;
import com.gatekeeper.service.SysOperationLogService;
import org.springframework.stereotype.Service;

/**
 * 操作审计日志服务实现 — 负责后台操作审计日志的分页查询，
 * 支持按操作类型与操作模块过滤。
 */
@Service
public class SysOperationLogServiceImpl extends ServiceImpl<SysOperationLogMapper, SysOperationLog>
        implements SysOperationLogService {

    /**
     * 分页查询操作审计日志，支持按操作类型与操作模块筛选
     *
     * @param current         当前页码
     * @param size            每页条数
     * @param operationType   操作类型（精确匹配，可为空）
     * @param operationModule 操作模块（精确匹配，可为空）
     * @return 操作日志分页结果
     */
    @Override
    public PageResult<SysOperationLog> pageQuery(int current, int size, String operationType, String operationModule) {
        Page<SysOperationLog> page = new Page<>(current, size);
        QueryWrapper<SysOperationLog> wrapper = new QueryWrapper<>();
        if (operationType != null && !operationType.isEmpty()) {
            wrapper.eq("operation_type", operationType); // 按操作类型过滤
        }
        if (operationModule != null && !operationModule.isEmpty()) {
            wrapper.eq("operation_module", operationModule); // 按操作模块过滤
        }
        wrapper.orderByDesc("created_at"); // 按创建时间倒序
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}
