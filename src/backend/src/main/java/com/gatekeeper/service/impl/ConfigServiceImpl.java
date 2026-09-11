package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.SysConfigDto;
import com.gatekeeper.entity.SysConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.SysConfigMapper;
import com.gatekeeper.service.ConfigService;
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
 * 系统参数配置服务实现 — T05 sys-config 参数配置能力
 *
 * <p>校验点：
 * <ul>
 *   <li>config_key 全局唯一（DB uk_config_key 兜底）</li>
 *   <li>built_in=1 禁止删除（定义层抛 GatewayException.badRequest）</li>
 *   <li>sensitive=1 的 config_value 在响应中脱敏为 "******"（仅展示脱敏，落库仍为明文）</li>
 *   <li>status 入参保留兼容，sys_config 无 status 列，不参与过滤</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig> implements ConfigService {

    /** 敏感配置响应脱敏占位符 */
    private static final String MASKED_VALUE = "******";

    @Override
    public PageResult<SysConfig> pageQuery(int pageNum, int pageSize, String keyword, String configGroup, Integer status) {
        if (pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize < 1) {
            pageSize = 10;
        }
        Page<SysConfig> page = new Page<>(pageNum, pageSize);
        QueryWrapper<SysConfig> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like("config_key", keyword)
                    .or().like("config_name", keyword)
                    .or().like("config_group", keyword));
        }
        if (StringUtils.hasText(configGroup)) {
            wrapper.eq("config_group", configGroup);
        }
        // 注：sys_config 无 status 列，status 入参不参与过滤（前端兼容保留）
        wrapper.orderByAsc("id");
        baseMapper.selectPage(page, wrapper);

        List<SysConfig> records = new ArrayList<>(page.getRecords().size());
        for (SysConfig c : page.getRecords()) {
            records.add(applyMask(c));
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public List<SysConfig> listAll(String configGroup) {
        QueryWrapper<SysConfig> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(configGroup)) {
            wrapper.eq("config_group", configGroup);
        }
        wrapper.orderByAsc("id");
        List<SysConfig> list = baseMapper.selectList(wrapper);
        List<SysConfig> result = new ArrayList<>(list.size());
        for (SysConfig c : list) {
            result.add(applyMask(c));
        }
        return result;
    }

    @Override
    public SysConfig getConfig(Long id) {
        if (id == null) {
            return null;
        }
        return applyMask(baseMapper.selectById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysConfig createConfig(SysConfigDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (!StringUtils.hasText(dto.getConfigKey())) {
            throw GatewayException.badRequest("配置键不能为空");
        }
        SysConfig exists = baseMapper.selectOne(
                new QueryWrapper<SysConfig>().eq("config_key", dto.getConfigKey()));
        if (exists != null) {
            throw GatewayException.badRequest("配置键已存在: " + dto.getConfigKey());
        }
        SysConfig cfg = new SysConfig();
        BeanUtils.copyProperties(dto, cfg);
        cfg.setId(null); // 创建场景忽略 id
        cfg.setCreatedAt(LocalDateTime.now());
        cfg.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(cfg);
        log.info("SysConfig created: id={}, configKey={}", cfg.getId(), cfg.getConfigKey());
        return applyMask(cfg);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(SysConfigDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getId() == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        SysConfig existing = baseMapper.selectById(dto.getId());
        if (existing == null) {
            throw GatewayException.notFound("配置不存在: id=" + dto.getId());
        }
        // 配置键唯一性（排除自身）
        if (StringUtils.hasText(dto.getConfigKey()) && !dto.getConfigKey().equals(existing.getConfigKey())) {
            SysConfig dup = baseMapper.selectOne(
                    new QueryWrapper<SysConfig>().eq("config_key", dto.getConfigKey()));
            if (dup != null) {
                throw GatewayException.badRequest("配置键已存在: " + dto.getConfigKey());
            }
        }
        // 仅允许修改的字段（builtIn 内置标识不可改，保护种子）
        if (StringUtils.hasText(dto.getConfigKey())) {
            existing.setConfigKey(dto.getConfigKey());
        }
        existing.setConfigValue(dto.getConfigValue());
        existing.setConfigGroup(dto.getConfigGroup());
        existing.setConfigName(dto.getConfigName());
        existing.setSensitive(dto.getSensitive());
        existing.setRemark(dto.getRemark());
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("SysConfig updated: id={}", existing.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfig(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("配置ID不能为空");
        }
        SysConfig existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("配置不存在: id=" + id);
        }
        if (existing.getBuiltIn() != null && existing.getBuiltIn() == 1) {
            throw GatewayException.badRequest("内置配置不可删除");
        }
        baseMapper.deleteById(id);
        log.info("SysConfig deleted: id={}, configKey={}", id, existing.getConfigKey());
    }

    /**
     * 敏感配置脱敏：sensitive=1 时 config_value 替换为固定占位符。
     *
     * @param config 配置实体（可为 null）
     * @return 脱敏后的同一实体（便于链式返回）
     */
    private SysConfig applyMask(SysConfig config) {
        if (config != null && config.getSensitive() != null && config.getSensitive() == 1) {
            config.setConfigValue(MASKED_VALUE);
        }
        return config;
    }
}
