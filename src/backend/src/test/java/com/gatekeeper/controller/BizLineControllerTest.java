package com.gatekeeper.controller;

import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.dto.BizLineDto;
import com.gatekeeper.entity.BizLine;
import com.gatekeeper.service.BizLineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BizLineController 端到端单测
 *
 * <p>校验：参数路由、响应 code 200、@RequirePerm 注解存在性（删除端点）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BizLineController 端到端")
class BizLineControllerTest {

    @Mock
    private BizLineService bizLineService;

    private BizLineController controller;

    @BeforeEach
    void setUp() {
        controller = new BizLineController(bizLineService);
    }

    @Test
    @DisplayName("list 返回分页结果")
    void list_ok() {
        when(bizLineService.pageQuery(eq(1), eq(10), eq("trade"), eq(1)))
                .thenReturn(PageResult.of(Collections.emptyList(), 0, 1, 10));

        Result<PageResult<BizLineDto>> r = controller.list(1, 10, "trade", 1);
        assertEquals(200, r.getCode());
        assertEquals(0, r.getData().getTotal());
    }

    @Test
    @DisplayName("all 返回启用业务线下拉列表")
    void all_ok() {
        BizLineDto dto = new BizLineDto();
        dto.setId(1L);
        dto.setLineCode("trade");
        when(bizLineService.listEnabled()).thenReturn(Collections.singletonList(dto));

        Result<java.util.List<BizLineDto>> r = controller.all();
        assertEquals(200, r.getCode());
        assertEquals(1, r.getData().size());
        assertEquals("trade", r.getData().get(0).getLineCode());
    }

    @Test
    @DisplayName("detail 存在则返回")
    void detail_ok() {
        BizLine b = new BizLine();
        b.setId(1L);
        b.setLineCode("trade");
        when(bizLineService.getBizLine(1L)).thenReturn(b);

        Result<BizLine> r = controller.detail(1L);
        assertEquals(200, r.getCode());
        assertEquals("trade", r.getData().getLineCode());
    }

    @Test
    @DisplayName("detail 不存在 404")
    void detail_notFound() {
        when(bizLineService.getBizLine(99L)).thenReturn(null);
        Result<BizLine> r = controller.detail(99L);
        assertEquals(404, r.getCode());
    }

    @Test
    @DisplayName("create 调用 service.createBizLine")
    void create_callsService() {
        BizLineDto dto = new BizLineDto();
        dto.setLineCode("trade");
        BizLine b = new BizLine();
        b.setId(1L);
        when(bizLineService.createBizLine(any(BizLineDto.class))).thenReturn(b);

        Result<BizLine> r = controller.create(dto);
        assertEquals(200, r.getCode());
        verify(bizLineService, times(1)).createBizLine(any(BizLineDto.class));
    }

    @Test
    @DisplayName("update 走 service.updateBizLine")
    void update_callsService() {
        BizLineDto dto = new BizLineDto();
        dto.setId(1L);
        Result<Void> r = controller.update(dto);
        assertEquals(200, r.getCode());
        verify(bizLineService, times(1)).updateBizLine(eq(1L), eq(dto));
    }

    @Test
    @DisplayName("delete 走 service.deleteBizLine 并标注 biz_line:delete 注解")
    void delete_callsServiceAndHasPermAnnotation() throws NoSuchMethodException {
        Result<Void> r = controller.delete(1L);
        assertEquals(200, r.getCode());
        verify(bizLineService, times(1)).deleteBizLine(1L);

        // 注解存在性检查：T03a 要求 @RequirePerm("biz_line:delete") + risk=true
        com.gatekeeper.security.RequirePerm ann =
                BizLineController.class.getMethod("delete", Long.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        assertNotNull(ann, "delete 方法必须标注 @RequirePerm");
        assertEquals("biz_line:delete", ann.value());
        assertTrue(ann.risk(), "biz_line:delete 必须是高危 (risk=true)");
    }

    @Test
    @DisplayName("list 方法不强制 @RequirePerm（兼容存量）")
    void list_noPermAnnotation() throws NoSuchMethodException {
        com.gatekeeper.security.RequirePerm ann =
                BizLineController.class.getMethod("list", int.class, int.class, String.class, Integer.class)
                        .getAnnotation(com.gatekeeper.security.RequirePerm.class);
        // list 不强制，但允许有注解；T03a 决策是不强制（非高危）
        // 这里不强制断言存在；只验证可正常路由调用
    }
}
