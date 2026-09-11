package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.entity.ApiVersion;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiVersionMapper;
import com.gatekeeper.service.ApiVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 接口版本服务实现 — T03b 接口生命周期子资源之一
 *
 * <p>校验点：
 * <ul>
 *   <li>create 必填 apiId / version，按 uk_version_api 唯一预检</li>
 *   <li>setCurrent 事务：同接口其余版本 is_current 清零，本版本置 1（EXACTLY ONE）</li>
 *   <li>deprecate / offline 仅改生命周期状态</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiVersionServiceImpl extends ServiceImpl<ApiVersionMapper, ApiVersion> implements ApiVersionService {

    @Override
    public List<ApiVersionDto> list(Long apiId) {
        QueryWrapper<ApiVersion> wrapper = new QueryWrapper<>();
        if (apiId != null) {
            wrapper.eq("api_id", apiId);
        }
        wrapper.orderByAsc("id");
        return baseMapper.selectList(wrapper).stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public ApiVersionDto get(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("版本ID不能为空");
        }
        ApiVersion entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("接口版本不存在: id=" + id);
        }
        return toDto(entity);
    }

    @Override
    public ApiVersionDto current(Long apiId) {
        if (apiId == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        ApiVersion entity = baseMapper.selectOne(
                new QueryWrapper<ApiVersion>().eq("api_id", apiId).eq("is_current", 1));
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiVersionDto create(ApiVersionDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getApiId() == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        if (!StringUtils.hasText(dto.getVersion())) {
            throw GatewayException.badRequest("版本号不能为空");
        }
        // 唯一性预检（DB uk_version_api 是兜底）
        ApiVersion exists = baseMapper.selectOne(
                new QueryWrapper<ApiVersion>().eq("api_id", dto.getApiId()).eq("version", dto.getVersion()));
        if (exists != null) {
            throw GatewayException.badRequest("该接口已存在相同版本: " + dto.getVersion());
        }
        ApiVersion entity = new ApiVersion();
        BeanUtils.copyProperties(dto, entity);
        entity.setId(null); // 创建场景忽略 id
        if (entity.getStatus() == null) {
            entity.setStatus(1);
        }
        if (entity.getIsCurrent() == null) {
            entity.setIsCurrent(0);
        }
        if (entity.getGrayRatio() == null) {
            entity.setGrayRatio(0);
        }
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(entity);
        log.info("ApiVersion created: id={}, apiId={}, version={}", entity.getId(), entity.getApiId(), entity.getVersion());
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setCurrent(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("版本ID不能为空");
        }
        ApiVersion target = baseMapper.selectById(id);
        if (target == null) {
            throw GatewayException.notFound("接口版本不存在: id=" + id);
        }
        Long apiId = target.getApiId();
        // 1) 同接口其余 is_current=1 的版本清零
        ApiVersion reset = new ApiVersion();
        reset.setIsCurrent(0);
        reset.setUpdatedAt(LocalDateTime.now());
        baseMapper.update(reset, new QueryWrapper<ApiVersion>().eq("api_id", apiId).eq("is_current", 1));
        // 2) 本版本置为当前（EXACTLY ONE current）
        target.setIsCurrent(1);
        target.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(target);
        log.info("ApiVersion setCurrent: id={}, apiId={}", id, apiId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deprecate(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("版本ID不能为空");
        }
        ApiVersion entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("接口版本不存在: id=" + id);
        }
        entity.setStatus(2);
        entity.setDeprecateTime(LocalDate.now());
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(entity);
        log.info("ApiVersion deprecated: id={}, version={}", id, entity.getVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void offline(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("版本ID不能为空");
        }
        ApiVersion entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("接口版本不存在: id=" + id);
        }
        entity.setStatus(3);
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(entity);
        log.info("ApiVersion offline: id={}, version={}", id, entity.getVersion());
    }

    /**
     * Entity → DTO。
     */
    private ApiVersionDto toDto(ApiVersion entity) {
        if (entity == null) {
            return null;
        }
        ApiVersionDto dto = new ApiVersionDto();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
