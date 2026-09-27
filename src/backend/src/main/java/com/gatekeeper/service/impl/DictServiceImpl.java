package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.SysDictDto;
import com.gatekeeper.dto.SysDictItemDto;
import com.gatekeeper.entity.SysDict;
import com.gatekeeper.entity.SysDictItem;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.SysDictItemMapper;
import com.gatekeeper.mapper.SysDictMapper;
import com.gatekeeper.service.DictService;
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
 * 数据字典服务实现 — T05 sys-dict 字典管理能力
 *
 * <p>校验点：
 * <ul>
 *   <li>dict_code 全局唯一（DB uk_dict_code 兜底）</li>
 *   <li>dict_code + item_value 唯一（DB 唯一键兜底）</li>
 *   <li>built_in=1 禁止删除字典（级联清理字典项避免孤儿数据）</li>
 *   <li>状态语义：0=停用, 1=启用</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictServiceImpl extends ServiceImpl<SysDictMapper, SysDict> implements DictService {

    private final SysDictItemMapper sysDictItemMapper;

    @Override
    public PageResult<SysDict> pageQuery(int pageNum, int pageSize, String keyword, Integer status) {
        if (pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize < 1) {
            pageSize = 10;
        }
        Page<SysDict> page = new Page<>(pageNum, pageSize);
        QueryWrapper<SysDict> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like("dict_code", keyword).or().like("dict_name", keyword));
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByAsc("id");
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
    @Override
    public List<SysDictItem> listItems(String dictCode) {
        if (!StringUtils.hasText(dictCode)) {
            return new ArrayList<>();
        }
        return sysDictItemMapper.selectList(
                new QueryWrapper<SysDictItem>().eq("dict_code", dictCode).orderByAsc("sort_order"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysDict createDict(SysDictDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (!StringUtils.hasText(dto.getDictCode())) {
            throw GatewayException.badRequest("字典编码不能为空");
        }
        SysDict exists = baseMapper.selectOne(new QueryWrapper<SysDict>().eq("dict_code", dto.getDictCode()));
        if (exists != null) {
            throw GatewayException.badRequest("字典编码已存在: " + dto.getDictCode());
        }
        SysDict dict = new SysDict();
        BeanUtils.copyProperties(dto, dict);
        dict.setId(null);
        if (dict.getStatus() == null) {
            dict.setStatus(1);
        }
        dict.setCreatedAt(LocalDateTime.now());
        dict.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(dict);
        log.info("SysDict created: id={}, dictCode={}", dict.getId(), dict.getDictCode());
        return dict;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDict(SysDictDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getId() == null) {
            throw GatewayException.badRequest("字典ID不能为空");
        }
        SysDict existing = baseMapper.selectById(dto.getId());
        if (existing == null) {
            throw GatewayException.notFound("字典不存在: id=" + dto.getId());
        }
        if (StringUtils.hasText(dto.getDictCode()) && !dto.getDictCode().equals(existing.getDictCode())) {
            SysDict dup = baseMapper.selectOne(new QueryWrapper<SysDict>().eq("dict_code", dto.getDictCode()));
            if (dup != null) {
                throw GatewayException.badRequest("字典编码已存在: " + dto.getDictCode());
            }
        }
        if (StringUtils.hasText(dto.getDictCode())) {
            existing.setDictCode(dto.getDictCode());
        }
        existing.setDictName(dto.getDictName());
        existing.setStatus(dto.getStatus());
        existing.setRemark(dto.getRemark());
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("SysDict updated: id={}", existing.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDict(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("字典ID不能为空");
        }
        SysDict existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("字典不存在: id=" + id);
        }
        if (existing.getBuiltIn() != null && existing.getBuiltIn() == 1) {
            throw GatewayException.badRequest("内置字典不可删除");
        }
        // 级联删除字典项（无外键约束，手动清理避免孤儿数据）
        sysDictItemMapper.delete(new QueryWrapper<SysDictItem>().eq("dict_code", existing.getDictCode()));
        baseMapper.deleteById(id);
        log.info("SysDict deleted: id={}, dictCode={}", id, existing.getDictCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysDictItem addItem(SysDictItemDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (!StringUtils.hasText(dto.getDictCode())) {
            throw GatewayException.badRequest("字典编码不能为空");
        }
        if (!StringUtils.hasText(dto.getItemValue())) {
            throw GatewayException.badRequest("字典项值不能为空");
        }
        SysDictItem dup = sysDictItemMapper.selectOne(new QueryWrapper<SysDictItem>()
                .eq("dict_code", dto.getDictCode()).eq("item_value", dto.getItemValue()));
        if (dup != null) {
            throw GatewayException.badRequest("字典项下值已存在: " + dto.getItemValue());
        }
        SysDictItem item = new SysDictItem();
        BeanUtils.copyProperties(dto, item);
        item.setId(null);
        if (item.getSortOrder() == null) {
            item.setSortOrder(0);
        }
        if (item.getStatus() == null) {
            item.setStatus(1);
        }
        sysDictItemMapper.insert(item);
        log.info("SysDictItem added: id={}, dictCode={}, itemValue={}", item.getId(), item.getDictCode(), item.getItemValue());
        return item;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateItem(SysDictItemDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getId() == null) {
            throw GatewayException.badRequest("字典项ID不能为空");
        }
        SysDictItem existing = sysDictItemMapper.selectById(dto.getId());
        if (existing == null) {
            throw GatewayException.notFound("字典项不存在: id=" + dto.getId());
        }
        if (StringUtils.hasText(dto.getItemValue()) && !dto.getItemValue().equals(existing.getItemValue())) {
            SysDictItem dup = sysDictItemMapper.selectOne(new QueryWrapper<SysDictItem>()
                    .eq("dict_code", existing.getDictCode()).eq("item_value", dto.getItemValue()));
            if (dup != null) {
                throw GatewayException.badRequest("字典项下值已存在: " + dto.getItemValue());
            }
        }
        if (StringUtils.hasText(dto.getDictCode())) {
            existing.setDictCode(dto.getDictCode());
        }
        existing.setItemValue(dto.getItemValue());
        existing.setItemLabel(dto.getItemLabel());
        existing.setSortOrder(dto.getSortOrder());
        existing.setStatus(dto.getStatus());
        sysDictItemMapper.updateById(existing);
        log.info("SysDictItem updated: id={}", existing.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(Long itemId) {
        if (itemId == null) {
            throw GatewayException.badRequest("字典项ID不能为空");
        }
        SysDictItem existing = sysDictItemMapper.selectById(itemId);
        if (existing == null) {
            throw GatewayException.notFound("字典项不存在: id=" + itemId);
        }
        sysDictItemMapper.deleteById(itemId);
        log.info("SysDictItem deleted: id={}", itemId);
    }
}
