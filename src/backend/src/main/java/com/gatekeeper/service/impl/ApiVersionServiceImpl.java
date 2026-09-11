package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.entity.ApiEnvConfig;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiVersion;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.ApiVersionMapper;
import com.gatekeeper.service.ApiVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
 *   <li>publish 前必须有已验证环境（api_env_config.config_status=2），并同步接口发布态</li>
 *   <li>setGray 仅当前版本可设，比例 0-100</li>
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

    /** 环境配置「已验证」状态值（原型 apiEnvConfigs.configStatus） */
    private static final int CONFIG_STATUS_VERIFIED = 2;

    /** 接口发布状态「已发布」（原型 api_status） */
    private static final int PUBLISH_STATUS_PUBLISHED = 2;

    /** 版本状态「生效中」 */
    private static final int VERSION_STATUS_ACTIVE = 1;

    /**
     * 环境配置 Mapper（publish 校验「已验证环境」用）。
     *
     * <p>使用字段注入以保留无参构造，兼容既有单测。</p>
     */
    @Autowired
    private ApiEnvConfigMapper apiEnvConfigMapper;

    /**
     * 接口 Mapper（publish 同步 api_interface.publish_status / current_version 用）。
     */
    @Autowired
    private ApiInterfaceMapper apiInterfaceMapper;

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
    public ApiVersionDto publish(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("版本ID不能为空");
        }
        ApiVersion target = baseMapper.selectById(id);
        if (target == null) {
            throw GatewayException.notFound("接口版本不存在: id=" + id);
        }
        Long apiId = target.getApiId();

        // 1) 发布前校验：至少 1 个「已验证」环境配置
        Long verifiedCount = apiEnvConfigMapper.selectCount(
                new QueryWrapper<ApiEnvConfig>()
                        .eq("api_id", apiId)
                        .eq("config_status", CONFIG_STATUS_VERIFIED));
        if (verifiedCount == null || verifiedCount == 0L) {
            throw GatewayException.badRequest("发布前必须至少配置 1 个已验证的环境地址（configStatus=2）");
        }

        // 2) 其余版本 is_current 清零
        ApiVersion reset = new ApiVersion();
        reset.setIsCurrent(0);
        reset.setUpdatedAt(LocalDateTime.now());
        baseMapper.update(reset, new QueryWrapper<ApiVersion>().eq("api_id", apiId).eq("is_current", 1));

        // 3) 本版本置 current + 生效中
        target.setIsCurrent(1);
        target.setStatus(VERSION_STATUS_ACTIVE);
        target.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(target);

        // 4) 同步接口发布态（publish_status=2 已发布 + current_version）
        ApiInterface iface = new ApiInterface();
        iface.setId(apiId);
        iface.setPublishStatus(PUBLISH_STATUS_PUBLISHED);
        iface.setCurrentVersion(target.getVersion());
        iface.setUpdatedAt(LocalDateTime.now());
        apiInterfaceMapper.updateById(iface);

        log.info("ApiVersion publish: id={}, apiId={}, version={}", id, apiId, target.getVersion());
        return toDto(target);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiVersionDto setGray(Long id, Integer grayRatio) {
        if (id == null) {
            throw GatewayException.badRequest("版本ID不能为空");
        }
        if (grayRatio == null || grayRatio < 0 || grayRatio > 100) {
            throw GatewayException.badRequest("灰度比例必须在 0-100 之间");
        }
        ApiVersion target = baseMapper.selectById(id);
        if (target == null) {
            throw GatewayException.notFound("接口版本不存在: id=" + id);
        }
        if (target.getIsCurrent() == null || target.getIsCurrent() != 1) {
            throw GatewayException.badRequest("仅当前版本（isCurrent=1）可设置灰度比例");
        }
        target.setGrayRatio(grayRatio);
        target.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(target);
        log.info("ApiVersion setGray: id={}, grayRatio={}", id, grayRatio);
        return toDto(target);
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
