package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.BizLineDto;
import com.gatekeeper.entity.BizLine;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.AppMapper;
import com.gatekeeper.mapper.BizLineMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BizLineServiceImpl 单元测试 — T03a 业务线服务
 *
 * <p>覆盖：CRUD + lineCode 唯一性 + 引用检查 + lineCode 不可改校验。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BizLineService CRUD + 业务规则")
class BizLineServiceTest {

    @Mock
    private BizLineMapper bizLineMapper;

    @Mock
    private AppMapper appMapper;

    private BizLineServiceImpl bizLineService;

    @BeforeEach
    void setUp() {
        bizLineService = new BizLineServiceImpl(appMapper);
        try {
            Field baseMapperField = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            baseMapperField.setAccessible(true);
            baseMapperField.set(bizLineService, bizLineMapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("listEnabled 返回 status=1 的业务线")
    void listEnabled_returnsActiveOnly() {
        BizLine b = new BizLine();
        b.setId(1L);
        b.setLineCode("trade");
        b.setStatus(1);
        when(bizLineMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(b));

        List<BizLineDto> result = bizLineService.listEnabled();
        assertEquals(1, result.size());
        assertEquals("trade", result.get(0).getLineCode());
    }

    @Test
    @DisplayName("pageQuery 入参兜底：pageNum<1 → 1，pageSize<1 → 10")
    void pageQuery_paramNormalization() {
        when(bizLineMapper.selectPage(any(com.baomidou.mybatisplus.extension.plugins.pagination.Page.class),
                any(QueryWrapper.class)))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<BizLine>(1, 10));
        PageResult<BizLineDto> r = bizLineService.pageQuery(0, 0, null, null);
        assertNotNull(r);
        assertEquals(10, r.getSize());
    }

    @Test
    @DisplayName("createBizLine 成功：写入 DB 并返回带 id 的实体")
    void createBizLine_success() {
        BizLineDto dto = new BizLineDto();
        dto.setLineCode("trade");
        dto.setLineName("交易业务线");
        when(bizLineMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        BizLine result = bizLineService.createBizLine(dto);
        assertEquals("trade", result.getLineCode());
        assertEquals(1, result.getStatus());
        assertEquals(0, result.getMemberCount());
        verify(bizLineMapper, times(1)).insert(any(BizLine.class));
    }

    @Test
    @DisplayName("createBizLine 重复 lineCode 抛业务异常")
    void createBizLine_duplicateFails() {
        BizLine existing = new BizLine();
        existing.setId(99L);
        existing.setLineCode("trade");
        when(bizLineMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        BizLineDto dto = new BizLineDto();
        dto.setLineCode("trade");
        dto.setLineName("交易业务线");

        GatewayException ex = assertThrows(GatewayException.class,
                () -> bizLineService.createBizLine(dto));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("createBizLine lineCode 为空抛 400")
    void createBizLine_blankLineCode() {
        BizLineDto dto = new BizLineDto();
        dto.setLineCode("");
        dto.setLineName("测试");
        GatewayException ex = assertThrows(GatewayException.class,
                () -> bizLineService.createBizLine(dto));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("updateBizLine 试图修改 lineCode 被拒绝（架构 D1 同语义：lineCode 创建后不可改）")
    void updateBizLine_rejectsLineCodeChange() {
        BizLine existing = new BizLine();
        existing.setId(1L);
        existing.setLineCode("trade");
        when(bizLineMapper.selectById(1L)).thenReturn(existing);

        BizLineDto dto = new BizLineDto();
        dto.setId(1L);
        dto.setLineCode("OTHER"); // 试图改
        dto.setLineName("改名");

        GatewayException ex = assertThrows(GatewayException.class,
                () -> bizLineService.updateBizLine(1L, dto));
        assertTrue(ex.getMessage().contains("不可修改"));
    }

    @Test
    @DisplayName("deleteBizLine 有应用引用时拒绝（refCount > 0）")
    void deleteBizLine_rejectsWhenAppReferenced() {
        BizLine existing = new BizLine();
        existing.setId(1L);
        existing.setLineCode("trade");
        when(bizLineMapper.selectById(1L)).thenReturn(existing);
        when(appMapper.countByLineId(1L)).thenReturn(3);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> bizLineService.deleteBizLine(1L));
        assertTrue(ex.getMessage().contains("应用引用"));
        // 关键：不应调用 deleteById
        verify(bizLineMapper, times(0)).deleteById(1L);
    }

    @Test
    @DisplayName("deleteBizLine 无应用引用时直接删")
    void deleteBizLine_succeedsWhenNoReference() {
        BizLine existing = new BizLine();
        existing.setId(1L);
        existing.setLineCode("trade");
        when(bizLineMapper.selectById(1L)).thenReturn(existing);
        when(appMapper.countByLineId(1L)).thenReturn(0);

        bizLineService.deleteBizLine(1L);
        verify(bizLineMapper, times(1)).deleteById(1L);
    }

    @Test
    @DisplayName("deleteBizLine 不存在时 404")
    void deleteBizLine_notFound() {
        when(bizLineMapper.selectById(99L)).thenReturn(null);
        GatewayException ex = assertThrows(GatewayException.class,
                () -> bizLineService.deleteBizLine(99L));
        assertEquals(404, ex.getCode());
    }
}
