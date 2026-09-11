package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.dto.ApiEnvConfigDto;
import com.gatekeeper.entity.ApiEnvConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import com.gatekeeper.service.ApiEnvConfigService;
import com.gatekeeper.util.UpstreamProber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
 *   <li>create/upsert 必填 apiId / envCode / upstreamUrl，且 upstreamUrl 必须是 http(s):// 开头</li>
 *   <li>configStatus 状态机：0=未配置, 1=已配置, 2=已验证（连通性测试通过）</li>
 *   <li>upsert 按 api_id + env_code + version（NULL 安全）唯一匹配：命中更新，未命中插入</li>
 *   <li>update 仅允许修改可编辑字段，configStatus 随 upstreamUrl 自动重算</li>
 *   <li>testConnectivity 通过 UpstreamProber 发 HEAD，成功置 2、失败置 1</li>
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

    /** configStatus：已配置（地址已填但未验证） */
    private static final int STATUS_CONFIGURED = 1;
    /** configStatus：已验证（连通性测试通过） */
    private static final int STATUS_VERIFIED = 2;

    /**
     * 上游连通性探测器（testConnectivity 用）。
     *
     * <p>字段注入以保留无参构造，兼容既有单测。</p>
     */
    @Autowired
    private UpstreamProber upstreamProber;

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
        validateUpstreamUrl(dto.getUpstreamUrl());
        // 唯一性预检（DB uk_api_env_ver 是兜底；version NULL 时按 IS NULL 处理）
        ApiEnvConfig exists = baseMapper.selectOne(uniqueQuery(dto.getApiId(), dto.getEnvCode(), dto.getVersion()));
        if (exists != null) {
            throw GatewayException.badRequest("该接口在该环境+版本下已存在配置");
        }
        ApiEnvConfig entity = new ApiEnvConfig();
        BeanUtils.copyProperties(dto, entity);
        entity.setId(null); // 创建场景忽略 id
        // 业务规则：有地址 → 已配置(1)；无地址 → 未配置(0)
        entity.setConfigStatus(StringUtils.hasText(entity.getUpstreamUrl()) ? STATUS_CONFIGURED : 0);
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
    public ApiEnvConfigDto upsert(ApiEnvConfigDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getApiId() == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        if (!StringUtils.hasText(dto.getEnvCode())) {
            throw GatewayException.badRequest("环境编码不能为空");
        }
        validateUpstreamUrl(dto.getUpstreamUrl());

        ApiEnvConfig existing = baseMapper.selectOne(
                uniqueQuery(dto.getApiId(), dto.getEnvCode(), dto.getVersion()));
        if (existing == null) {
            // 命中不到 → 走创建
            ApiEnvConfig entity = new ApiEnvConfig();
            BeanUtils.copyProperties(dto, entity);
            entity.setId(null);
            entity.setConfigStatus(STATUS_CONFIGURED);
            if (entity.getMockEnabled() == null) {
                entity.setMockEnabled(0);
            }
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            baseMapper.insert(entity);
            log.info("ApiEnvConfig upsert-insert: id={}, apiId={}, envCode={}, version={}",
                    entity.getId(), entity.getApiId(), entity.getEnvCode(), entity.getVersion());
            return toDto(entity);
        }
        // 命中 → 更新可编辑字段
        String oldUrl = existing.getUpstreamUrl();
        existing.setUpstreamUrl(dto.getUpstreamUrl());
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
        // 地址发生变更 → 验证态失效，重置为「已配置」
        if (!dto.getUpstreamUrl().equals(oldUrl)) {
            existing.setConfigStatus(STATUS_CONFIGURED);
        }
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("ApiEnvConfig upsert-update: id={}, apiId={}, envCode={}", existing.getId(), existing.getApiId(), existing.getEnvCode());
        return toDto(existing);
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
            validateUpstreamUrl(dto.getUpstreamUrl());
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
        // configStatus 随 upstreamUrl 自动重算（有地址 → 已配置 1；无地址 → 未配置 0）
        existing.setConfigStatus(StringUtils.hasText(existing.getUpstreamUrl()) ? STATUS_CONFIGURED : 0);
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("ApiEnvConfig updated: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiEnvConfigDto testConnectivity(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        ApiEnvConfig entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("接口环境配置不存在: id=" + id);
        }
        boolean ok = upstreamProber != null
                && upstreamProber.probe(entity.getUpstreamUrl(), UpstreamProber.DEFAULT_TIMEOUT_MS);
        entity.setConfigStatus(ok ? STATUS_VERIFIED : STATUS_CONFIGURED);
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(entity);
        log.info("ApiEnvConfig testConnectivity: id={}, url={}, ok={}, configStatus={}",
                id, entity.getUpstreamUrl(), ok, entity.getConfigStatus());
        return toDto(entity);
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

    // =====================================================================
    // 内部工具
    // =====================================================================

    /**
     * 构造 (apiId, envCode, version) 唯一匹配条件（version 为 null 时用 IS NULL）。
     */
    private QueryWrapper<ApiEnvConfig> uniqueQuery(Long apiId, String envCode, String version) {
        QueryWrapper<ApiEnvConfig> q = new QueryWrapper<ApiEnvConfig>()
                .eq("api_id", apiId)
                .eq("env_code", envCode);
        if (version == null) {
            q.isNull("version");
        } else {
            q.eq("version", version);
        }
        return q;
    }

    /**
     * 校验后端服务地址：非空且以 http:// 或 https:// 开头。
     */
    private void validateUpstreamUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw GatewayException.badRequest("后端服务地址不能为空");
        }
        String lower = url.trim().toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw GatewayException.badRequest("后端服务地址必须以 http:// 或 https:// 开头");
        }
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
