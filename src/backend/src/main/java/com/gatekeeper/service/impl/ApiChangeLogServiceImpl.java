package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.dto.ApiChangeLogDto;
import com.gatekeeper.entity.ApiChangeLog;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiChangeLogMapper;
import com.gatekeeper.service.ApiChangeLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 接口变更历史服务实现 — T03b 接口生命周期子资源之一（追加型，只读）
 *
 * <p>校验点：
 * <ul>
 *   <li>append 必填 apiId / changeType</li>
 *   <li>append 服务端填充 createTime=now（不可由调用方指定）</li>
 *   <li>无 update / delete（审计凭证，仅追加）</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiChangeLogServiceImpl extends ServiceImpl<ApiChangeLogMapper, ApiChangeLog> implements ApiChangeLogService {

    @Override
    public List<ApiChangeLogDto> list(Long apiId, String changeType) {
        QueryWrapper<ApiChangeLog> wrapper = new QueryWrapper<>();
        if (apiId != null) {
            wrapper.eq("api_id", apiId);
        }
        if (StringUtils.hasText(changeType)) {
            wrapper.eq("change_type", changeType);
        }
        wrapper.orderByDesc("create_time");
        return baseMapper.selectList(wrapper).stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public ApiChangeLogDto get(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("变更记录ID不能为空");
        }
        ApiChangeLog entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("变更记录不存在: id=" + id);
        }
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiChangeLogDto append(ApiChangeLogDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getApiId() == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        if (!StringUtils.hasText(dto.getChangeType())) {
            throw GatewayException.badRequest("变更类型不能为空");
        }
        ApiChangeLog entity = new ApiChangeLog();
        BeanUtils.copyProperties(dto, entity);
        entity.setId(null); // 追加场景忽略 id
        // 服务端填充变更时间（不可由调用方指定，保证审计时序准确）
        entity.setCreateTime(LocalDateTime.now());
        baseMapper.insert(entity);
        log.info("ApiChangeLog appended: id={}, apiId={}, changeType={}", entity.getId(), entity.getApiId(), entity.getChangeType());
        return toDto(entity);
    }

    /**
     * Entity → DTO。
     */
    private ApiChangeLogDto toDto(ApiChangeLog entity) {
        if (entity == null) {
            return null;
        }
        ApiChangeLogDto dto = new ApiChangeLogDto();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
