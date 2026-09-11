package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.EnvDto;
import com.gatekeeper.entity.Env;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.EnvMapper;
import com.gatekeeper.service.EnvService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境服务实现 — T03a 三大基础域之一
 *
 * <p>架构 D1：env_code 一旦创建不可修改（防止冗余列不一致）。
 * 删/停用不影响存量凭证/授权（历史数据继续可用，只是不能新建）。</p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvServiceImpl extends ServiceImpl<EnvMapper, Env> implements EnvService {

    /** env_code 合法白名单（与 prototype 一致） */
    private static final java.util.regex.Pattern ENV_CODE_PATTERN =
            java.util.regex.Pattern.compile("^(dev|test|pre|prod)$");

    @Override
    public PageResult<EnvDto> pageQuery(int pageNum, int pageSize, String envCode, Integer status) {
        if (pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize < 1) {
            pageSize = 10;
        }
        Page<Env> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Env> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(envCode)) {
            wrapper.like("env_code", envCode);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        // 排序：sort_order 升序，id 升序兜底
        wrapper.orderByAsc("sort_order").orderByAsc("id");
        baseMapper.selectPage(page, wrapper);

        List<EnvDto> records = new ArrayList<>(page.getRecords().size());
        for (Env e : page.getRecords()) {
            records.add(toDto(e));
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public List<EnvDto> listEnabled() {
        List<Env> list = baseMapper.selectList(
                new QueryWrapper<Env>().eq("status", 1).orderByAsc("sort_order").orderByAsc("id"));
        List<EnvDto> result = new ArrayList<>(list.size());
        for (Env e : list) {
            result.add(toDto(e));
        }
        return result;
    }

    @Override
    public Env getEnv(Long id) {
        if (id == null) {
            return null;
        }
        return baseMapper.selectById(id);
    }

    @Override
    public Env getByEnvCode(String envCode) {
        if (!StringUtils.hasText(envCode)) {
            return null;
        }
        return baseMapper.selectOne(new QueryWrapper<Env>().eq("env_code", envCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Env createEnv(EnvDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (!StringUtils.hasText(dto.getEnvCode()) || !ENV_CODE_PATTERN.matcher(dto.getEnvCode()).matches()) {
            throw GatewayException.badRequest("环境编码仅支持 dev/test/pre/prod");
        }
        if (!StringUtils.hasText(dto.getEnvName())) {
            throw GatewayException.badRequest("环境名称不能为空");
        }
        if (!StringUtils.hasText(dto.getGatewayUrl())) {
            throw GatewayException.badRequest("网关地址不能为空");
        }
        // 唯一性预检（DB uk_env_code 兜底）
        Env exists = baseMapper.selectOne(new QueryWrapper<Env>().eq("env_code", dto.getEnvCode()));
        if (exists != null) {
            throw GatewayException.badRequest("环境编码已存在: " + dto.getEnvCode());
        }
        Env env = new Env();
        BeanUtils.copyProperties(dto, env);
        env.setId(null);
        env.setCreatedAt(LocalDateTime.now());
        env.setUpdatedAt(LocalDateTime.now());
        if (env.getStatus() == null) {
            env.setStatus(1);
        }
        if (env.getSortOrder() == null) {
            env.setSortOrder(99);
        }
        baseMapper.insert(env);
        log.info("Env created: id={}, envCode={}", env.getId(), env.getEnvCode());
        return env;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEnv(Long id, EnvDto dto) {
        if (id == null) {
            throw GatewayException.badRequest("环境ID不能为空");
        }
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        Env existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("环境不存在: id=" + id);
        }
        // 架构 D1：env_code 一旦创建不可修改（即便值相同也按"无修改"处理）
        if (StringUtils.hasText(dto.getEnvCode()) && !dto.getEnvCode().equals(existing.getEnvCode())) {
            throw GatewayException.badRequest("环境编码创建后不可修改（架构 D1）");
        }
        existing.setEnvName(dto.getEnvName());
        existing.setGatewayUrl(dto.getGatewayUrl());
        existing.setSortOrder(dto.getSortOrder());
        existing.setStatus(dto.getStatus());
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("Env updated: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteEnv(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("环境ID不能为空");
        }
        Env existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("环境不存在: id=" + id);
        }
        // 架构 D1：删/停用不影响存量凭证/授权，不在这里做引用校验
        baseMapper.deleteById(id);
        log.info("Env deleted: id={}, envCode={}", id, existing.getEnvCode());
    }

    /**
     * Entity → DTO，并从 gatewayUrl 推导 https 标记。
     */
    private EnvDto toDto(Env e) {
        if (e == null) {
            return null;
        }
        EnvDto dto = new EnvDto();
        BeanUtils.copyProperties(e, dto);
        // 推导 https 字段（UI 展示「http/https」标签）
        if (dto.getGatewayUrl() !=
                null && dto.getGatewayUrl().toLowerCase().startsWith("https://")) {
            dto.setHttps(1);
        } else {
            dto.setHttps(0);
        }
        return dto;
    }
}
