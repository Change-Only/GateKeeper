package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.BizLineDto;
import com.gatekeeper.entity.BizLine;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.AppMapper;
import com.gatekeeper.mapper.BizLineMapper;
import com.gatekeeper.service.BizLineService;
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
 * 业务线服务实现 — T03a 三大基础域之一
 *
 * <p>校验点：
 * <ul>
 *   <li>lineCode 全局唯一（DB 唯一键 + service 预检）</li>
 *   <li>删除前校验应用引用计数 = 0</li>
 *   <li>update / delete 不允许传递 id=null</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BizLineServiceImpl extends ServiceImpl<BizLineMapper, BizLine> implements BizLineService {

    private final AppMapper appMapper;

    @Override
    public PageResult<BizLineDto> pageQuery(int pageNum, int pageSize, String keyword, Integer status) {
        // 入参兜底：避免非法分页参数击穿 MyBatis-Plus
        if (pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize < 1) {
            pageSize = 10;
        }
        Page<BizLine> page = new Page<>(pageNum, pageSize);
        QueryWrapper<BizLine> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            // 同时模糊 lineCode / lineName（OR）
            wrapper.and(w -> w.like("line_code", keyword).or().like("line_name", keyword));
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByAsc("id");
        baseMapper.selectPage(page, wrapper);

        List<BizLineDto> records = new ArrayList<>(page.getRecords().size());
        for (BizLine b : page.getRecords()) {
            records.add(toDto(b));
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public List<BizLineDto> listEnabled() {
        List<BizLine> list = baseMapper.selectList(
                new QueryWrapper<BizLine>().eq("status", 1).orderByAsc("id"));
        List<BizLineDto> result = new ArrayList<>(list.size());
        for (BizLine b : list) {
            result.add(toDto(b));
        }
        return result;
    }

    @Override
    public BizLine getBizLine(Long id) {
        if (id == null) {
            return null;
        }
        return baseMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BizLine createBizLine(BizLineDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (!StringUtils.hasText(dto.getLineCode())) {
            throw GatewayException.badRequest("业务线编码不能为空");
        }
        if (!StringUtils.hasText(dto.getLineName())) {
            throw GatewayException.badRequest("业务线名称不能为空");
        }
        // 唯一性预检（DB uk_bizline_code 是兜底）
        BizLine exists = baseMapper.selectOne(
                new QueryWrapper<BizLine>().eq("line_code", dto.getLineCode()));
        if (exists != null) {
            throw GatewayException.badRequest("业务线编码已存在: " + dto.getLineCode());
        }
        BizLine biz = new BizLine();
        BeanUtils.copyProperties(dto, biz);
        biz.setId(null); // 创建场景忽略 id
        biz.setCreatedAt(LocalDateTime.now());
        biz.setUpdatedAt(LocalDateTime.now());
        if (biz.getStatus() == null) {
            biz.setStatus(1);
        }
        if (biz.getMemberCount() == null) {
            biz.setMemberCount(0);
        }
        baseMapper.insert(biz);
        log.info("BizLine created: id={}, lineCode={}", biz.getId(), biz.getLineCode());
        return biz;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateBizLine(Long id, BizLineDto dto) {
        if (id == null) {
            throw GatewayException.badRequest("业务线ID不能为空");
        }
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        BizLine existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("业务线不存在: id=" + id);
        }
        // lineCode 一旦创建不可修改（避免历史 line_id 引用混乱）
        if (StringUtils.hasText(dto.getLineCode()) && !dto.getLineCode().equals(existing.getLineCode())) {
            throw GatewayException.badRequest("业务线编码创建后不可修改");
        }
        // 仅允许修改的字段（拷贝前先把 lineCode / createdAt 抹掉，防止恶意改）
        existing.setLineName(dto.getLineName());
        existing.setOwnerName(dto.getOwnerName());
        existing.setMemberCount(dto.getMemberCount());
        existing.setStatus(dto.getStatus());
        existing.setRemark(dto.getRemark());
        existing.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(existing);
        log.info("BizLine updated: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteBizLine(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("业务线ID不能为空");
        }
        BizLine existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("业务线不存在: id=" + id);
        }
        // 引用检查：若存在 app.line_id == id，禁止删除
        int refCount = appMapper.countByLineId(id);
        if (refCount > 0) {
            throw GatewayException.badRequest(
                    "业务线仍有 " + refCount + " 个应用引用，无法删除（lineCode=" + existing.getLineCode() + "）");
        }
        baseMapper.deleteById(id);
        log.info("BizLine deleted: id={}, lineCode={}", id, existing.getLineCode());
    }

    /**
     * Entity → DTO。
     */
    private BizLineDto toDto(BizLine b) {
        if (b == null) {
            return null;
        }
        BizLineDto dto = new BizLineDto();
        BeanUtils.copyProperties(b, dto);
        return dto;
    }
}
