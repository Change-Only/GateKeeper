package com.gatekeeper.controller;

import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.dto.BizLineDto;
import com.gatekeeper.entity.BizLine;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.BizLineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 业务线管理 Controller — T03a 业务线主数据对外能力
 *
 * <p>接口路径（T03a）：
 * <ul>
 *   <li>GET    /api/biz-line/list          分页查询</li>
 *   <li>GET    /api/biz-line/all           全量（启用）—— 下拉用</li>
 *   <li>GET    /api/biz-line/{id}          详情</li>
 *   <li>POST   /api/biz-line/create        新建</li>
 *   <li>PUT    /api/biz-line/update        更新</li>
 *   <li>DELETE /api/biz-line/{id}          删除（{@code biz_line:delete} 高危）</li>
 * </ul></p>
 *
 * <p>权限点：
 * <ul>
 *   <li>delete / high risk: {@code biz_line:delete}</li>
 *   <li>其它：非高危，不强制 @RequirePerm（保持兼容）</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@RestController
@RequestMapping("/biz-line")
@RequiredArgsConstructor
@Tag(name = "业务线管理", description = "业务线主数据管理")
public class BizLineController {

    private final BizLineService bizLineService;

    /**
     * 分页查询业务线。
     *
     * @param pageNum  当前页码
     * @param pageSize 每页条数
     * @param keyword  关键字（模糊 lineCode/lineName）
     * @param status   状态（可空）
     */
    @Operation(summary = "分页查询业务线")
    @GetMapping("/list")
    public Result<PageResult<BizLineDto>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return Result.success(bizLineService.pageQuery(pageNum, pageSize, keyword, status));
    }

    /**
     * 全量启用业务线（下拉用）。
     */
    @Operation(summary = "全量业务线（下拉）")
    @GetMapping("/all")
    public Result<List<BizLineDto>> all() {
        return Result.success(bizLineService.listEnabled());
    }

    /**
     * 业务线详情。
     */
    @Operation(summary = "业务线详情")
    @GetMapping("/{id}")
    public Result<BizLine> detail(@PathVariable Long id) {
        BizLine biz = bizLineService.getBizLine(id);
        return biz == null ? Result.notFound("业务线不存在") : Result.success(biz);
    }

    /**
     * 新建业务线。
     */
    @Operation(summary = "新建业务线")
    @PostMapping("/create")
    public Result<BizLine> create(@Valid @RequestBody BizLineDto dto) {
        return Result.success(bizLineService.createBizLine(dto));
    }

    /**
     * 更新业务线（lineCode 不可改）。
     */
    @Operation(summary = "更新业务线")
    @PutMapping("/update")
    public Result<Void> update(@Valid @RequestBody BizLineDto dto) {
        bizLineService.updateBizLine(dto.getId(), dto);
        return Result.success();
    }

    /**
     * 删除业务线（高危）。
     *
     * <p>T03a 权限点 {@code biz_line:delete}（risk=1）。</p>
     */
    @RequirePerm(value = "biz_line:delete", risk = true)
    @Operation(summary = "删除业务线")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        bizLineService.deleteBizLine(id);
        return Result.success();
    }
}
