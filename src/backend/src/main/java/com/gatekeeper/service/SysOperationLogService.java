package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.OperationLogOptionsVo;
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

    /**
     * 查询审计页筛选下拉的候选项（库里**真实出现过**的操作模块 / 操作类型去重值）。
     *
     * <p>这两个值域分别由 {@code OperationLogAspect#firstSegment(requestURI)} 与
     * HTTP 方法推导，会随后续 controller 的增减而变化 —— 前端不得写死，
     * 否则下拉必然与数据失同步（2026-09-17 实测：前端 5 项 vs 库里 18 项）。</p>
     *
     * @return {@link OperationLogOptionsVo}，两字段均非 null（无数据时为空列表）
     */
    OperationLogOptionsVo filterOptions();
}
