package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.ApiChangeLogDto;
import com.gatekeeper.dto.ApiEnvConfigDto;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.dto.InterfaceDetailVo;
import com.gatekeeper.dto.InterfaceListVo;
import com.gatekeeper.entity.ApiChangeLog;
import com.gatekeeper.entity.ApiEnvConfig;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.entity.ApiVersion;
import com.gatekeeper.entity.BizLine;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiChangeLogMapper;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.ApiParamMapper;
import com.gatekeeper.mapper.ApiVersionMapper;
import com.gatekeeper.mapper.BizLineMapper;
import com.gatekeeper.service.InterfaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接口管理服务实现 — 负责 API 接口的分页查询、创建、更新、启停与删除。
 *
 * <p>T03b 扩展：列表带分组名/业务线名、详情聚合、删除前置校验（版本全下线）。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterfaceServiceImpl extends ServiceImpl<ApiInterfaceMapper, ApiInterface> implements InterfaceService {

    /** paramType：Header */
    private static final int PT_HEADER = 1;
    /** paramType：Request 入参 */
    private static final int PT_REQUEST = 3;
    /** paramType：Response 出参 */
    private static final int PT_RESPONSE = 4;
    /** paramType：Error 错误码 */
    private static final int PT_ERROR = 5;

    /** 变更历史详情聚合：最近 N 条 */
    private static final int RECENT_CHANGE_LOG_LIMIT = 5;

    private final ApiGroupMapper apiGroupMapper;
    private final BizLineMapper bizLineMapper;
    private final ApiParamMapper apiParamMapper;
    private final ApiVersionMapper apiVersionMapper;
    private final ApiEnvConfigMapper apiEnvConfigMapper;
    private final ApiChangeLogMapper apiChangeLogMapper;

    @Override
    public PageResult<ApiInterface> pageQuery(int current, int size, String interfaceName, Long groupId) {
        Page<ApiInterface> page = new Page<>(current, size);
        QueryWrapper<ApiInterface> wrapper = new QueryWrapper<>();
        if (interfaceName != null && !interfaceName.isEmpty()) {
            wrapper.like("interface_name", interfaceName); // 接口名称模糊匹配
        }
        if (groupId != null) {
            wrapper.eq("group_id", groupId); // 按分组精确筛选
        }
        wrapper.orderByDesc("created_at"); // 按创建时间倒序
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public PageResult<InterfaceListVo> pageQueryEnriched(int current, int size, String interfaceName, Long groupId) {
        // 入参兜底
        int pageNum = current < 1 ? 1 : current;
        int pageSize = size < 1 ? 10 : size;
        Page<ApiInterface> page = new Page<>(pageNum, pageSize);
        QueryWrapper<ApiInterface> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(interfaceName)) {
            wrapper.like("interface_name", interfaceName);
        }
        if (groupId != null) {
            wrapper.eq("group_id", groupId);
        }
        wrapper.orderByDesc("created_at");
        baseMapper.selectPage(page, wrapper);

        List<ApiInterface> rows = page.getRecords();
        Map<Long, String> groupNames = loadGroupNames(rows);
        Map<Long, String> lineNames = loadLineNames(rows);
        List<InterfaceListVo> vos = new ArrayList<>(rows.size());
        for (ApiInterface r : rows) {
            vos.add(toListVo(r, groupNames, lineNames));
        }
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public ApiInterface createInterface(ApiInterface apiInterface) {
        apiInterface.setStatus(1); // 默认启用
        if (apiInterface.getTimeoutMs() == null) {
            apiInterface.setTimeoutMs(5000); // 默认超时 5000 毫秒
        }
        // T03b：发布态默认草稿（0），避免未定义参数即被当作已发布
        if (apiInterface.getPublishStatus() == null) {
            apiInterface.setPublishStatus(0);
        }
        if (apiInterface.getVisibility() == null) {
            apiInterface.setVisibility(1);
        }
        if (apiInterface.getAuthRequired() == null) {
            apiInterface.setAuthRequired(1);
        }
        if (apiInterface.getTransportSecurity() == null) {
            apiInterface.setTransportSecurity("NONE");
        }
        if (apiInterface.getGrantCount() == null) {
            apiInterface.setGrantCount(0);
        }
        apiInterface.setCreatedAt(LocalDateTime.now());
        apiInterface.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(apiInterface);
        log.info("ApiInterface created: id={}, name={}", apiInterface.getId(), apiInterface.getInterfaceName());
        return apiInterface;
    }

    @Override
    public void updateInterface(Long id, ApiInterface apiInterface) {
        apiInterface.setId(id);
        apiInterface.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(apiInterface);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        ApiInterface iface = new ApiInterface();
        iface.setId(id);
        iface.setStatus(status);
        iface.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(iface);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteInterface(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        ApiInterface api = baseMapper.selectById(id);
        if (api == null) {
            throw GatewayException.notFound("接口不存在: id=" + id);
        }
        // 前置校验：必须已下线全部版本（仅 status=3 已下线 的版本不算占用）
        Long active = apiVersionMapper.selectCount(
                new QueryWrapper<ApiVersion>().eq("api_id", id).ne("status", 3));
        if (active != null && active > 0L) {
            throw GatewayException.badRequest("该接口仍有 " + active + " 个未下线版本，请先下线全部版本再删除");
        }
        baseMapper.deleteById(id);
        log.info("ApiInterface deleted: id={}, name={}", id, api.getInterfaceName());
    }

    @Override
    public InterfaceDetailVo getDetail(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        ApiInterface api = baseMapper.selectById(id);
        if (api == null) {
            throw GatewayException.notFound("接口不存在: id=" + id);
        }
        InterfaceDetailVo vo = new InterfaceDetailVo();
        vo.setApi(api);
        vo.setHeader(paramDtos(id, PT_HEADER));
        vo.setRequest(paramDtos(id, PT_REQUEST));
        vo.setResponse(paramDtos(id, PT_RESPONSE));
        vo.setError(paramDtos(id, PT_ERROR));
        vo.setVersions(versionDtos(id));
        vo.setEnvConfigs(envConfigDtos(id));
        vo.setRecentChangeLogs(changeLogDtos(id, RECENT_CHANGE_LOG_LIMIT));
        return vo;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    private Map<Long, String> loadGroupNames(List<ApiInterface> rows) {
        Set<Long> ids = new HashSet<>();
        for (ApiInterface r : rows) {
            if (r.getGroupId() != null) {
                ids.add(r.getGroupId());
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ApiGroup> groups = apiGroupMapper.selectBatchIds(ids);
        Map<Long, String> map = new HashMap<>();
        if (groups != null) {
            for (ApiGroup g : groups) {
                map.put(g.getId(), g.getGroupName());
            }
        }
        return map;
    }

    private Map<Long, String> loadLineNames(List<ApiInterface> rows) {
        Set<Long> ids = new HashSet<>();
        for (ApiInterface r : rows) {
            if (r.getLineId() != null) {
                ids.add(r.getLineId());
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<BizLine> lines = bizLineMapper.selectBatchIds(ids);
        Map<Long, String> map = new HashMap<>();
        if (lines != null) {
            for (BizLine b : lines) {
                map.put(b.getId(), b.getLineName());
            }
        }
        return map;
    }

    private InterfaceListVo toListVo(ApiInterface r, Map<Long, String> groupNames, Map<Long, String> lineNames) {
        InterfaceListVo vo = new InterfaceListVo();
        BeanUtils.copyProperties(r, vo);
        if (r.getGroupId() != null) {
            vo.setGroupName(groupNames.get(r.getGroupId()));
        }
        if (r.getLineId() != null) {
            vo.setLineName(lineNames.get(r.getLineId()));
        }
        return vo;
    }

    private List<ApiParamDto> paramDtos(Long apiId, int paramType) {
        List<ApiParam> list = apiParamMapper.selectList(
                new QueryWrapper<ApiParam>()
                        .eq("api_id", apiId)
                        .eq("param_type", paramType)
                        .orderByAsc("sort_order")
                        .orderByAsc("id"));
        List<ApiParamDto> dtos = new ArrayList<>(list.size());
        for (ApiParam p : list) {
            ApiParamDto d = new ApiParamDto();
            BeanUtils.copyProperties(p, d);
            dtos.add(d);
        }
        return dtos;
    }

    private List<ApiVersionDto> versionDtos(Long apiId) {
        List<ApiVersion> list = apiVersionMapper.selectList(
                new QueryWrapper<ApiVersion>().eq("api_id", apiId).orderByAsc("id"));
        List<ApiVersionDto> dtos = new ArrayList<>(list.size());
        for (ApiVersion v : list) {
            ApiVersionDto d = new ApiVersionDto();
            BeanUtils.copyProperties(v, d);
            dtos.add(d);
        }
        return dtos;
    }

    private List<ApiEnvConfigDto> envConfigDtos(Long apiId) {
        List<ApiEnvConfig> list = apiEnvConfigMapper.selectList(
                new QueryWrapper<ApiEnvConfig>().eq("api_id", apiId).orderByAsc("id"));
        List<ApiEnvConfigDto> dtos = new ArrayList<>(list.size());
        for (ApiEnvConfig e : list) {
            ApiEnvConfigDto d = new ApiEnvConfigDto();
            BeanUtils.copyProperties(e, d);
            dtos.add(d);
        }
        return dtos;
    }

    private List<ApiChangeLogDto> changeLogDtos(Long apiId, int limit) {
        List<ApiChangeLog> list = apiChangeLogMapper.selectList(
                new QueryWrapper<ApiChangeLog>()
                        .eq("api_id", apiId)
                        .orderByDesc("create_time")
                        .last("LIMIT " + limit));
        List<ApiChangeLogDto> dtos = new ArrayList<>(list.size());
        for (ApiChangeLog c : list) {
            ApiChangeLogDto d = new ApiChangeLogDto();
            BeanUtils.copyProperties(c, d);
            dtos.add(d);
        }
        return dtos;
    }
}
