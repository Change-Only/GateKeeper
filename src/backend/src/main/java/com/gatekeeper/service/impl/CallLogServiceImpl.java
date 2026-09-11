package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.entity.ApiCallLog;
import com.gatekeeper.mapper.ApiCallLogMapper;
import com.gatekeeper.service.CallLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 调用日志服务实现 — 负责 API 调用日志的多条件分页查询与详情查看，
 * 支持按应用、接口、响应状态、客户端 IP、限流/拦截标记及请求时间范围过滤。
 */
@Service
public class CallLogServiceImpl extends ServiceImpl<ApiCallLogMapper, ApiCallLog> implements CallLogService {

    /**
     * 分页查询调用日志，支持多维条件组合过滤
     *
     * @param current        当前页码
     * @param size           每页条数
     * @param appId          应用 ID（可为空）
     * @param interfaceId    接口 ID（可为空）
     * @param responseStatus 响应状态码（可为空）
     * @param clientIp       客户端 IP（精确匹配，可为空）
     * @param isRateLimited  是否被限流（可为空）
     * @param isBlocked      是否被拦截（可为空）
     * @param startTime      请求开始时间（可为空）
     * @param endTime        请求结束时间（可为空）
     * @return 调用日志分页结果
     */
    @Override
    public PageResult<ApiCallLog> pageQuery(int current, int size, Long appId, Long interfaceId,
                                            Integer responseStatus, String clientIp,
                                            Boolean isRateLimited, Boolean isBlocked,
                                            LocalDateTime startTime, LocalDateTime endTime) {
        Page<ApiCallLog> page = new Page<>(current, size);
        QueryWrapper<ApiCallLog> wrapper = new QueryWrapper<>();
        if (appId != null) {
            wrapper.eq("app_id", appId); // 按应用过滤
        }
        if (interfaceId != null) {
            wrapper.eq("interface_id", interfaceId); // 按接口过滤
        }
        if (responseStatus != null) {
            wrapper.eq("response_status", responseStatus); // 按响应状态过滤
        }
        if (clientIp != null && !clientIp.isEmpty()) {
            wrapper.eq("client_ip", clientIp); // 按客户端 IP 过滤
        }
        if (isRateLimited != null) {
            wrapper.eq("is_rate_limited", isRateLimited); // 按是否限流过滤
        }
        if (isBlocked != null) {
            wrapper.eq("is_blocked", isBlocked); // 按是否拦截过滤
        }
        if (startTime != null) {
            wrapper.ge("request_time", startTime); // 请求时间下界
        }
        if (endTime != null) {
            wrapper.le("request_time", endTime); // 请求时间上界
        }
        wrapper.orderByDesc("request_time"); // 按请求时间倒序
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 查询单条调用日志详情
     *
     * @param id 日志 ID
     * @return 日志实体，不存在时返回 null
     */
    @Override
    public ApiCallLog getDetail(Long id) {
        return baseMapper.selectById(id);
    }
}
