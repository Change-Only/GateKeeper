package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.ApiCallLog;

import java.time.LocalDateTime;

/**
 * 调用日志服务接口 — 负责 API 调用日志的分页查询与详情查看业务
 */
public interface CallLogService extends IService<ApiCallLog> {

    /**
     * 按多条件分页查询调用日志
     *
     * @param current        当前页码
     * @param size           每页条数
     * @param appId          应用 ID（可为空）
     * @param interfaceId    接口 ID（可为空）
     * @param responseStatus 响应状态码（可为空）
     * @param clientIp       客户端 IP（可为空）
     * @param isRateLimited  是否被限流（可为空）
     * @param isBlocked      是否被拦截（可为空）
     * @param startTime      起始时间（可为空）
     * @param endTime        结束时间（可为空）
     * @return 调用日志分页结果
     */
    PageResult<ApiCallLog> pageQuery(int current, int size, Long appId, Long interfaceId,
                                     Integer responseStatus, String clientIp,
                                     Boolean isRateLimited, Boolean isBlocked,
                                     LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 查询单条调用日志详情
     *
     * @param id 日志 ID
     * @return 调用日志详情实体
     */
    ApiCallLog getDetail(Long id);
}
