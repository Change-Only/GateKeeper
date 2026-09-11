package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.dto.ApiEnvConfigDto;
import com.gatekeeper.entity.ApiEnvConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import com.gatekeeper.service.ApiEnvConfigService;
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
 * 接口环境配置服务实现 — T03b 接口生命周期子资源之一
 *
 * <p>校验点：
 * <ul>
 *   <li>create 必填 apiId / envCode / upstreamUrl，按 uk_api_env_ver 唯一预检（version NULL 特殊处理）</li>
 *   <li>configStatus 业务规则：upstreamUrl 存在 → 1（已配置），否则 → 2（未配置）</li>
 *   <li>update 仅允许修改可编辑字段，configStatus 随 upstreamUrl 自动重算</li>
 *   <li>toggleMock 翻转 mockEnabled 0↔1</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiEnvConfigServiceImpl extends ServiceImpl<ApiEnvConfigMapper, ApiEnvConfig> implements ApiEnvConfigService {

    @Override
    public List<ApiEnvConfigDto> list(Long apiId, String envCode) {
        QueryWrapper<ApiEnvConfig> wrapper = new QueryWrapper<>();
        if (apiId != null) {
            wrapper.eq("api_id", apiId);
        }
        if (StringUtils.hasText(envCode)) {
            wrapper.eq("env_code", envCode);
        }
        wrapper.orderByAsc("id");
        return baseMapper.selectList(wrapper).stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public ApiEnvConfigDto get(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        ApiEnvConfig entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("接口环境配置不存在: id=" + id);
        }
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiEnvConfigDto create(ApiEnvConfigDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getApiId() == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        if (!StringUtils.hasText(dto.getEnvCode())) {
            throw GatewayException.badRequest("环境编码不能为空");
        }
        if (!StringUtils.hasText(dto.getUpstreamUrl())) {
            throw GatewayException.badRequest("后端服务地址不能为空");
        }
        // 唯一性预检（DB uk_api_env_ver 是兜底；version NULL 时按 IS NULL 处理）
        QueryWrapper<ApiEnvConfig> existQuery = new QueryWrapper<ApiEnvConfig>()
                .eq("api_id", dto.getApiId())
                .eq("env_code", dto.getEnvCode());
        if (dto.getVersion() == null) {
            existQuery.isNull("version");
        } else {
            existQuery.eq("version", dto.getVersion());
        }
        ApiEnvConfig exists = baseMapper.selectOne(existQuery);
        if (exists != null) {
            throw GatewayException.badRequest("该接口在该环境+版本下已存在配置");
        }
        ApiEnvConfig entity = new ApiEnvConfig();
        BeanUtils.copyProperties(dto, entity);
        entity.setId(null); // 创建场景忽略 id
        // 业务规则：upstreamUrl 存在 → 已配置(1)，否则 → 未配置(2)
        entity.setConfigStatus(StringUtils.hasText(entity.getUpstreamUrl()) ? 1 : 2);
        if (entity.getMockEnabled() == null) {
            entity.setMockEnabled(0);
        }
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(entity);
        log.info("ApiEnvConfig created: id={}, apiId={}, envCode={}, version={}",
                entity.getId(), entity.getApiId(), entity.getEnvCode(), entity.getVersion());
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ApiEnvConfigDto dto) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        ApiEnvConfig existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("接口环境配置不存在: id=" + id);
        }
        // 仅允许修改的字段（结构键 apiId / envCode / version 不可改）
        if (StringUtils.hasText(dto.getUpstreamUrl())) {
            existing.setUpstreamUrl(dto.getUpstreamUrl());
        }
        if (dto.getConnectTimeout() != null) {
            existing.setConnectTimeout(dto.getConnectTimeout());
        }
        if (dto.getReadTimeout() != null) {
            existing.setReadTimeout(dto.getReadTimeout());
        }
        if (dto.getRetryCount() != null) {
            existing.setRetryCount(dto.getRetryCount());
        }
        if (dto.getMockEnabled() != null) {
            existing.setMockEnabled(dto.getMockEnabled());
        }
        // configStatus 随 upstreamUrl 自动重算
        existing.setConfigStatus(StringUtils.hasText(existing.getUpstreamUrl()) ? 1 : 2);
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("ApiEnvConfig updated: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleMock(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        ApiEnvConfig entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("接口环境配置不存在: id=" + id);
        }
        // 翻转 mockEnabled 0↔1
        int next = (entity.getMockEnabled() != null && entity.getMockEnabled() == 1) ? 0 : 1;
        entity.setMockEnabled(next);
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(entity);
        log.info("ApiEnvConfig toggleMock: id={}, mockEnabled={}", id, next);
    }

    /**
     * Entity → DTO。
     */
    private ApiEnvConfigDto toDto(ApiEnvConfig entity) {
        if (entity == null) {
            return null;
        }
        ApiEnvConfigDto dto = new ApiEnvConfigDto();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
