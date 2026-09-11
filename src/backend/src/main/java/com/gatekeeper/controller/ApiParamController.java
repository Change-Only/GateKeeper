package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.ApiParamService;
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
 * 接口参数管理 Controller — T03b 接口生命周期子资源之一
 *
 * <p>接口路径（T03b）：
 * <ul>
 *   <li>GET    /api-param/list                按 apiId/paramType/parentId 筛选</li>
 *   <li>GET    /api-param/tree                嵌套树（root = parentId 0）</li>
 *   <li>GET    /api-param/{id}                详情</li>
 *   <li>POST   /api-param/create              创建（{@code api_param:create} 高危）</li>
 *   <li>PUT    /api-param/{id}/update         更新可编辑字段</li>
 *   <li>DELETE /api-param/{id}                删除（{@code api_param:delete} 高危）</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@RestController
@RequestMapping("/api-param")
@RequiredArgsConstructor
@Tag(name = "接口参数", description = "接口参数定义管理（HEADER/QUERY/BODY/RESPONSE/ERROR_CODE，支持嵌套）")
public class ApiParamController {

    private final ApiParamService apiParamService;

    /**
     * 按接口ID / 参数类型 / 父级ID 筛选参数列表。
     */
    @Operation(summary = "查询接口参数列表")
    @GetMapping("/list")
    public Result<List<ApiParamDto>> list(
            @RequestParam(required = false) Long apiId,
            @RequestParam(required = false) Integer paramType,
            @RequestParam(required = false) Long parentId) {
        return Result.success(apiParamService.list(apiId, paramType, parentId));
    }

    /**
     * 构建某接口的嵌套参数树。
     */
    @Operation(summary = "查询接口参数树形结构")
    @GetMapping("/tree")
    public Result<List<ApiParamDto>> tree(@RequestParam Long apiId) {
        return Result.success(apiParamService.tree(apiId));
    }

    /**
     * 接口参数详情。
     */
    @Operation(summary = "接口参数详情")
    @GetMapping("/{id}")
    public Result<ApiParamDto> detail(@PathVariable Long id) {
        return Result.success(apiParamService.get(id));
    }

    /**
     * 创建接口参数（高危）。
     *
     * <p>T03b 权限点 {@code api_param:create}（risk=true）。</p>
     */
    @RequirePerm(value = "api_param:create", risk = true)
    @Operation(summary = "创建接口参数")
    @PostMapping("/create")
    public Result<ApiParamDto> create(@Valid @RequestBody ApiParamDto dto) {
        return Result.success(apiParamService.create(dto));
    }

    /**
     * 更新接口参数（仅可编辑字段）。
     */
    @Operation(summary = "更新接口参数")
    @PutMapping("/{id}/update")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ApiParamDto dto) {
        apiParamService.update(id, dto);
        return Result.success();
    }

    /**
     * 删除接口参数（高危）。
     *
     * <p>T03b 权限点 {@code api_param:delete}（risk=true）。</p>
     */
    @RequirePerm(value = "api_param:delete", risk = true)
    @Operation(summary = "删除接口参数")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        apiParamService.removeById(id);
        return Result.success();
    }
}
