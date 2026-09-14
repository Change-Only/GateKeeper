package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.dto.ApiGroupEnvConfigDto;
import com.gatekeeper.entity.ApiGroupEnvConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.EnvConfigResolver;
import com.gatekeeper.gateway.dto.EffectiveEnvConfig;
import com.gatekeeper.mapper.ApiGroupEnvConfigMapper;
import com.gatekeeper.service.ApiGroupEnvConfigService;
import com.gatekeeper.util.UpstreamProber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 接口分组环境配置服务实现 — T13
 *
 * <p>校验点：
 * <ul>
 *   <li>upsert/update 必填 groupId / envCode / upstreamUrl，且 upstreamUrl 必须是 http(s):// 开头</li>
 *   <li>唯一键是 (group_id, env_code)：upsert 按这两列匹配，天然幂等（本表**没有** version 维度）</li>
 *   <li>configStatus 由 upstreamUrl 推导（有地址 → 1 已配置；空 → 0 未配置），连通性测试通过置 2</li>
 *   <li>mockStatus 缺省 200（避免出现"HTTP 状态码 0"这种非法响应）</li>
 *   <li>effective() 与网关共用 {@link EnvConfigResolver}，不自己走父链</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiGroupEnvConfigServiceImpl
        extends ServiceImpl<ApiGroupEnvConfigMapper, ApiGroupEnvConfig>
        implements ApiGroupEnvConfigService {

    /** configStatus：未配置 */
    private static final int STATUS_NONE = 0;
    /** configStatus：已配置（地址已填但未验证） */
    private static final int STATUS_CONFIGURED = 1;
    /** configStatus：已验证（连通性测试通过） */
    private static final int STATUS_VERIFIED = 2;

    /** Mock 默认状态码 */
    private static final int DEFAULT_MOCK_STATUS = 200;

    /**
     * 上游连通性探测器（testConnectivity 用）。
     *
     * <p>字段注入以保留无参构造，兼容既有单测写法（与 ApiEnvConfigServiceImpl 同款）。</p>
     */
    @Autowired(required = false)
    private UpstreamProber upstreamProber;

    /** 生效配置解析器（分组继承链的唯一口径） */
    @Autowired
    private EnvConfigResolver envConfigResolver;

    @Override
    public List<ApiGroupEnvConfigDto> list(Long groupId, String envCode) {
        if (groupId == null) {
            throw GatewayException.badRequest("分组ID不能为空");
        }
        QueryWrapper<ApiGroupEnvConfig> wrapper = new QueryWrapper<ApiGroupEnvConfig>()
                .eq("group_id", groupId);
        if (StringUtils.hasText(envCode)) {
            wrapper.eq("env_code", envCode);
        }
        wrapper.orderByAsc("id");
        return baseMapper.selectList(wrapper).stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public ApiGroupEnvConfigDto get(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        ApiGroupEnvConfig entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("分组环境配置不存在: id=" + id);
        }
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiGroupEnvConfigDto upsert(ApiGroupEnvConfigDto dto) {
        validateForWrite(dto);
        ApiGroupEnvConfig existing = baseMapper.selectOne(new QueryWrapper<ApiGroupEnvConfig>()
                .eq("group_id", dto.getGroupId())
                .eq("env_code", dto.getEnvCode()));
        if (existing == null) {
            ApiGroupEnvConfig entity = new ApiGroupEnvConfig();
            BeanUtils.copyProperties(dto, entity);
            entity.setId(null);
            applyDefaults(entity);
            entity.setConfigStatus(STATUS_CONFIGURED);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            baseMapper.insert(entity);
            log.info("GroupEnvConfig created: id={}, groupId={}, env={}",
                    entity.getId(), entity.getGroupId(), entity.getEnvCode());
            return toDto(entity);
        }
        String oldUrl = existing.getUpstreamUrl();
        applyEditable(existing, dto);
        // 地址变更 → 之前的"已验证"失效（旧地址的连通性不能代表新地址）
        if (!dto.getUpstreamUrl().equals(oldUrl)) {
            existing.setConfigStatus(STATUS_CONFIGURED);
        }
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("GroupEnvConfig upsert-update: id={}, groupId={}, env={}",
                existing.getId(), existing.getGroupId(), existing.getEnvCode());
        return toDto(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ApiGroupEnvConfigDto dto) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        ApiGroupEnvConfig existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("分组环境配置不存在: id=" + id);
        }
        if (StringUtils.hasText(dto.getUpstreamUrl())) {
            validateUpstreamUrl(dto.getUpstreamUrl());
        }
        applyEditable(existing, dto);
        existing.setConfigStatus(StringUtils.hasText(existing.getUpstreamUrl())
                ? STATUS_CONFIGURED : STATUS_NONE);
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("GroupEnvConfig updated: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        ApiGroupEnvConfig existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("分组环境配置不存在: id=" + id);
        }
        baseMapper.deleteById(id);
        log.info("GroupEnvConfig deleted: id={}, groupId={}, env={}",
                id, existing.getGroupId(), existing.getEnvCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiGroupEnvConfigDto testConnectivity(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        ApiGroupEnvConfig entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("分组环境配置不存在: id=" + id);
        }
        int timeout = entity.getConnectTimeout() != null && entity.getConnectTimeout() > 0
                ? entity.getConnectTimeout() : UpstreamProber.DEFAULT_TIMEOUT_MS;
        boolean ok = upstreamProber != null && upstreamProber.probe(entity.getUpstreamUrl(), timeout);
        entity.setConfigStatus(ok ? STATUS_VERIFIED : STATUS_CONFIGURED);
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(entity);
        log.info("GroupEnvConfig testConnectivity: id={}, url={}, ok={}, configStatus={}",
                id, entity.getUpstreamUrl(), ok, entity.getConfigStatus());
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleMock(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        ApiGroupEnvConfig entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("分组环境配置不存在: id=" + id);
        }
        int next = (entity.getMockEnabled() != null && entity.getMockEnabled() == 1) ? 0 : 1;
        entity.setMockEnabled(next);
        if (entity.getMockStatus() == null) {
            entity.setMockStatus(DEFAULT_MOCK_STATUS);
        }
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(entity);
        log.info("GroupEnvConfig toggleMock: id={}, mockEnabled={}", id, next);
    }

    @Override
    public List<EffectiveEnvConfig> effective(Long groupId) {
        if (groupId == null) {
            throw GatewayException.badRequest("分组ID不能为空");
        }
        List<EffectiveEnvConfig> out = new ArrayList<>();
        for (String env : ENVS) {
            // 与网关共用同一个解析器：保证"页面显示的"就是"网关实际用的"
            out.add(envConfigResolver.resolveForGroup(groupId, env));
        }
        return out;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /** 写入前校验（upsert 用）：groupId / envCode / upstreamUrl 必填 + URL 合法性 */
    private void validateForWrite(ApiGroupEnvConfigDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getGroupId() == null) {
            throw GatewayException.badRequest("分组ID不能为空");
        }
        if (!StringUtils.hasText(dto.getEnvCode())) {
            throw GatewayException.badRequest("环境编码不能为空");
        }
        validateUpstreamUrl(dto.getUpstreamUrl());
    }

    /** 补默认值：mockStatus 缺省 200、mockEnabled 缺省 0 */
    private void applyDefaults(ApiGroupEnvConfig entity) {
        if (entity.getMockEnabled() == null) {
            entity.setMockEnabled(0);
        }
        if (entity.getMockStatus() == null) {
            entity.setMockStatus(DEFAULT_MOCK_STATUS);
        }
    }

    /**
     * 把入参里的可编辑字段写到实体上。
     *
     * <p>结构键 groupId / envCode 不允许改（配置的唯一身份），故此处忽略这两个字段。</p>
     */
    private void applyEditable(ApiGroupEnvConfig target, ApiGroupEnvConfigDto dto) {
        if (StringUtils.hasText(dto.getUpstreamUrl())) {
            target.setUpstreamUrl(dto.getUpstreamUrl());
        }
        if (dto.getConnectTimeout() != null) {
            target.setConnectTimeout(dto.getConnectTimeout());
        }
        if (dto.getReadTimeout() != null) {
            target.setReadTimeout(dto.getReadTimeout());
        }
        if (dto.getRetryCount() != null) {
            target.setRetryCount(dto.getRetryCount());
        }
        if (dto.getMockEnabled() != null) {
            target.setMockEnabled(dto.getMockEnabled());
        }
        if (dto.getMockStatus() != null) {
            target.setMockStatus(dto.getMockStatus());
        }
        if (dto.getMockResponse() != null) {
            target.setMockResponse(dto.getMockResponse());
        }
    }

    /** 校验服务前缀：非空且以 http:// 或 https:// 开头 */
    private void validateUpstreamUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw GatewayException.badRequest("服务前缀不能为空");
        }
        String lower = url.trim().toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw GatewayException.badRequest("服务前缀必须以 http:// 或 https:// 开头");
        }
    }

    /** Entity → DTO */
    private ApiGroupEnvConfigDto toDto(ApiGroupEnvConfig entity) {
        if (entity == null) {
            return null;
        }
        ApiGroupEnvConfigDto dto = new ApiGroupEnvConfigDto();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
