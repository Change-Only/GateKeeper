package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiParamMapper;
import com.gatekeeper.service.ApiParamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 接口参数服务实现 — T03b 接口生命周期子资源之一
 *
 * <p>校验点：
 * <ul>
 *   <li>create 必填 apiId / fieldName / paramType</li>
 *   <li>update 仅允许修改可编辑字段，结构键（apiId / parentId / paramType）不可改</li>
 *   <li>tree 基于 parent_id 构建嵌套树，根节点 parentId = 0</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiParamServiceImpl extends ServiceImpl<ApiParamMapper, ApiParam> implements ApiParamService {

    @Override
    public List<ApiParamDto> list(Long apiId, Integer paramType, Long parentId) {
        QueryWrapper<ApiParam> wrapper = new QueryWrapper<>();
        if (apiId != null) {
            wrapper.eq("api_id", apiId);
        }
        if (paramType != null) {
            wrapper.eq("param_type", paramType);
        }
        if (parentId != null) {
            wrapper.eq("parent_id", parentId);
        }
        wrapper.orderByAsc("sort_order").orderByAsc("id");
        return baseMapper.selectList(wrapper).stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public ApiParamDto get(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("参数ID不能为空");
        }
        ApiParam entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("接口参数不存在: id=" + id);
        }
        return toDto(entity);
    }

    @Override
    public List<ApiParamDto> tree(Long apiId) {
        if (apiId == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        // 一次性加载该接口下所有参数，内存构建树
        List<ApiParam> all = baseMapper.selectList(
                new QueryWrapper<ApiParam>().eq("api_id", apiId).orderByAsc("sort_order").orderByAsc("id"));

        Map<Long, ApiParamDto> dtoMap = new LinkedHashMap<>();
        for (ApiParam p : all) {
            dtoMap.put(p.getId(), toDto(p));
        }

        List<ApiParamDto> roots = new ArrayList<>();
        for (ApiParam p : all) {
            ApiParamDto dto = dtoMap.get(p.getId());
            long pid = (p.getParentId() == null) ? 0L : p.getParentId();
            if (pid == 0L) {
                roots.add(dto);
            } else {
                ApiParamDto parent = dtoMap.get(pid);
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(dto);
                } else {
                    // 父节点缺失（脏数据），兜底挂到根
                    roots.add(dto);
                }
            }
        }
        return roots;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiParamDto create(ApiParamDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getApiId() == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        if (!StringUtils.hasText(dto.getFieldName())) {
            throw GatewayException.badRequest("字段名不能为空");
        }
        if (dto.getParamType() == null) {
            throw GatewayException.badRequest("参数类型不能为空");
        }
        ApiParam entity = new ApiParam();
        BeanUtils.copyProperties(dto, entity);
        entity.setId(null); // 创建场景忽略 id
        if (entity.getParentId() == null) {
            entity.setParentId(0L);
        }
        if (entity.getRequired() == null) {
            entity.setRequired(0);
        }
        if (entity.getSensitive() == null) {
            entity.setSensitive(0);
        }
        if (entity.getSortOrder() == null) {
            entity.setSortOrder(0);
        }
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(entity);
        log.info("ApiParam created: id={}, apiId={}, fieldName={}", entity.getId(), entity.getApiId(), entity.getFieldName());
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ApiParamDto dto) {
        if (id == null) {
            throw GatewayException.badRequest("参数ID不能为空");
        }
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        ApiParam existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("接口参数不存在: id=" + id);
        }
        // 仅允许修改的字段（结构键 apiId / parentId / paramType 不可改）
        if (StringUtils.hasText(dto.getFieldName())) {
            existing.setFieldName(dto.getFieldName());
        }
        if (StringUtils.hasText(dto.getFieldType())) {
            existing.setFieldType(dto.getFieldType());
        }
        if (dto.getRequired() != null) {
            existing.setRequired(dto.getRequired());
        }
        if (StringUtils.hasText(dto.getExample())) {
            existing.setExample(dto.getExample());
        }
        if (StringUtils.hasText(dto.getErrorCode())) {
            existing.setErrorCode(dto.getErrorCode());
        }
        if (dto.getHttpStatus() != null) {
            existing.setHttpStatus(dto.getHttpStatus());
        }
        if (dto.getSensitive() != null) {
            existing.setSensitive(dto.getSensitive());
        }
        if (StringUtils.hasText(dto.getEncryptRule())) {
            existing.setEncryptRule(dto.getEncryptRule());
        }
        if (dto.getSortOrder() != null) {
            existing.setSortOrder(dto.getSortOrder());
        }
        if (StringUtils.hasText(dto.getDescription())) {
            existing.setDescription(dto.getDescription());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("ApiParam updated: id={}", id);
    }

    /**
     * Entity → DTO。
     */
    private ApiParamDto toDto(ApiParam entity) {
        if (entity == null) {
            return null;
        }
        ApiParamDto dto = new ApiParamDto();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
