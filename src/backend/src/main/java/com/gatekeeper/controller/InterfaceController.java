package com.gatekeeper.controller;

import com.gatekeeper.aspect.ApiChangeLog;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.dto.InterfaceDetailVo;
import com.gatekeeper.dto.InterfaceListVo;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.InterfaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 接口管理 Controller
 *
 * <p>T03b 升级：把接口从「转发的路径」升级为「接口资产」。
 * <ul>
 *   <li>GET    /interface/list                列表带分组名/业务线名（跨表冗余）</li>
 *   <li>GET    /interface/{apiId}             详情聚合（基本信息 + 参数 + 版本 + 环境配置 + 最近变更）</li>
 *   <li>POST   /interface                     新增接口（自动写变更历史 CREATE）</li>
 *   <li>PUT    /interface/{apiId}             编辑接口（自动写变更历史 UPDATE）</li>
 *   <li>PUT    /interface/{apiId}/status/{status} 启用/停用接口</li>
 *   <li>POST   /interface/{apiId}/publish     发布接口（{@code api:publish} 高危）</li>
 *   <li>DELETE /interface/{apiId}             删除接口（{@code api:delete} 高危，需先下线全部版本）</li>
 * </ul></p>
 *
 * <p>变更留痕：所有写操作用 {@code @ApiChangeLog} 注解，由
 * {@link com.gatekeeper.aspect.ApiChangeLogAspect} 在成功返回后自动追加 api_change_log。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@RestController
@RequestMapping("/interface")
@RequiredArgsConstructor
@Tag(name = "接口", description = "接口管理接口")
public class InterfaceController {

    private final InterfaceService interfaceService;

    /**
     * 分页查询接口列表（T03b 增强：行内含分组名 groupName / 业务线名 lineName）。
     *
     * @param current       当前页码（默认第 1 页）
     * @param size          每页条数（默认 10 条）
     * @param interfaceName 接口名称（可选，模糊匹配）
     * @param groupId       所属分组 ID（可选；传父分组时返回其全部子孙分组的接口）
     * @return 分页结果
     */
    @Operation(summary = "分页查询接口列表")
    @GetMapping("/list")
    public Result<PageResult<InterfaceListVo>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String interfaceName,
            @RequestParam(required = false) Long groupId) {
        return Result.success(interfaceService.pageQueryEnriched(current, size, interfaceName, groupId));
    }

    /**
     * 接口详情聚合（T03b 新增）。
     *
     * @param apiId 接口 ID
     * @return 聚合详情（基本信息 + 4 类参数 + 版本列表 + 环境配置 + 最近 5 条变更）
     */
    @Operation(summary = "接口详情聚合")
    @GetMapping("/{apiId}")
    public Result<InterfaceDetailVo> detail(@PathVariable Long apiId) {
        return Result.success(interfaceService.getDetail(apiId));
    }

    /**
     * 新增接口（自动写变更历史 CREATE）。
     *
     * @param apiInterface 接口实体（含名称、路径、所属分组等）
     * @return 创建成功后的接口实体
     */
    @ApiChangeLog(value = "新增接口", changeType = "CREATE", fieldName = "interface", fieldLabel = "接口")
    @Operation(summary = "新增接口")
    @RequirePerm(value = "api:create", risk = true)
    @PostMapping
    public Result<ApiInterface> create(@RequestBody ApiInterface apiInterface) {
        return Result.success(interfaceService.createInterface(apiInterface));
    }

    /**
     * 编辑接口信息（自动写变更历史 UPDATE）。
     *
     * @param apiId        接口 ID
     * @param apiInterface 待更新的接口信息
     * @return 操作结果
     */
    @ApiChangeLog(value = "编辑接口", changeType = "UPDATE", fieldName = "interface", fieldLabel = "接口")
    @Operation(summary = "更新接口")
    @RequirePerm(value = "api:update", risk = true)
    @PutMapping("/{apiId}")
    public Result<Void> update(@PathVariable Long apiId, @RequestBody ApiInterface apiInterface) {
        interfaceService.updateInterface(apiId, apiInterface);
        return Result.success();
    }

    /**
     * 启用/停用接口。
     *
     * @param apiId  接口 ID
     * @param status 目标状态（如 1 启用、0 停用）
     * @return 操作结果
     */
    @Operation(summary = "更新接口状态")
    @RequirePerm(value = "api:disable", risk = true)
    @PutMapping("/{apiId}/status/{status}")
    public Result<Void> updateStatus(@PathVariable Long apiId, @PathVariable Integer status) {
        interfaceService.updateStatus(apiId, status);
        return Result.success();
    }

    /**
     * 删除接口（高危，需先下线全部版本）。
     *
     * <p>T02 权限点：{@code api:delete}。T03b 增加「版本全下线」前置校验。</p>
     *
     * @param apiId 接口 ID
     * @return 操作结果
     */
    @ApiChangeLog(value = "删除接口", changeType = "DELETE", fieldName = "interface", fieldLabel = "接口")
    @RequirePerm(value = "api:delete", risk = true)
    @Operation(summary = "删除接口")
    @DeleteMapping("/{apiId}")
    public Result<Void> delete(@PathVariable Long apiId) {
        interfaceService.deleteInterface(apiId);
        return Result.success();
    }

    /**
     * 发布接口（高危）。
     *
     * <p>T02 新增接口，覆盖原型 {@code api:publish} 权限点。
     * 版本级发布请使用 {@code POST /api-version/{id}/publish}（T03b）。</p>
     *
     * @param apiId 接口 ID
     * @return 操作结果
     */
    @RequirePerm(value = "api:publish", risk = true)
    @Operation(summary = "发布接口")
    @PostMapping("/{apiId}/publish")
    public Result<Void> publish(@PathVariable Long apiId) {
        interfaceService.updateStatus(apiId, 1);
        return Result.success();
    }
}
