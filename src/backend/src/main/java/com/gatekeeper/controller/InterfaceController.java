package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.InterfaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 接口管理 Controller
 *
 * <p>负责 API 接口（ApiInterface）的增删改查管理，业务模块包括：接口的新增、编辑、
 * 启停、删除，以及按名称、所属分组进行分页查询。接口的加解密配置由独立的
 * {@link EncryptionConfigController} 管理。</p>
 *
 * <p>T02 收尾：发布接口、删除接口已打上 @RequirePerm 高危注解。</p>
 *
 * <p>主要接口路径前缀：{@code /interface}
 * <ul>
 *   <li>GET    /interface/list             分页查询接口列表</li>
 *   <li>POST   /interface                  新增接口</li>
 *   <li>PUT    /interface/{id}             编辑接口信息</li>
 *   <li>PUT    /interface/{id}/status/{status} 启用/停用接口</li>
 *   <li>POST   /interface/{id}/publish    发布接口（{@code api:publish} 高危）</li>
 *   <li>DELETE /interface/{id}             删除接口（{@code api:delete} 高危）</li>
 * </ul>
 */
@RestController
@RequestMapping("/interface")
@RequiredArgsConstructor
@Tag(name = "接口", description = "接口管理接口")
public class InterfaceController {

    private final InterfaceService interfaceService;

    /**
     * 分页查询接口列表
     *
     * @param current       当前页码（默认第 1 页）
     * @param size          每页条数（默认 10 条）
     * @param interfaceName 接口名称（可选，模糊匹配）
     * @param groupId       所属分组 ID（可选，按分组筛选）
     * @return 分页结果，包含接口列表及总数
     */
    @Operation(summary = "分页查询接口列表")
    @GetMapping("/list")
    public Result<PageResult<ApiInterface>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String interfaceName,
            @RequestParam(required = false) Long groupId) {
        return Result.success(interfaceService.pageQuery(current, size, interfaceName, groupId));
    }

    /**
     * 新增接口
     *
     * @param apiInterface 接口实体（含名称、路径、所属分组等）
     * @return 创建成功后的接口实体
     */
    @PostMapping
    public Result<ApiInterface> create(@RequestBody ApiInterface apiInterface) {
        return Result.success(interfaceService.createInterface(apiInterface));
    }

    /**
     * 编辑接口信息
     *
     * @param id            接口 ID
     * @param apiInterface  待更新的接口信息
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "更新接口")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ApiInterface apiInterface) {
        interfaceService.updateInterface(id, apiInterface);
        return Result.success();
    }

    /**
     * 启用/停用接口
     *
     * @param id     接口 ID
     * @param status 目标状态（如 1 启用、0 停用）
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "更新接口状态")
    @PutMapping("/{id}/status/{status}")
    public Result<Void> updateStatus(@PathVariable Long id, @PathVariable Integer status) {
        interfaceService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 删除接口（高危）
     *
     * <p>T02 权限点：{@code api:delete}</p>
     *
     * @param id 接口 ID
     * @return 操作结果（无业务数据返回）
     */
    @RequirePerm(value = "api:delete", risk = true)
    @Operation(summary = "删除接口")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        interfaceService.deleteInterface(id);
        return Result.success();
    }

    /**
     * 发布接口（高危）。
     *
     * <p>T02 新增接口，覆盖原型 {@code api:publish} 权限点。
     * MVP 实现：把接口 status 置 1（启用=发布），publish_status 由 T03 完整流转。
     * 真实生产流程（参数校验、版本生成、灰度路由规则下发）由 T03 接口域实现。</p>
     *
     * @param id 接口 ID
     * @return 操作结果（无业务数据返回）
     */
    @RequirePerm(value = "api:publish", risk = true)
    @Operation(summary = "发布接口")
    @PostMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        // T02 MVP：复用 updateStatus；T03 在 InterfaceService 中扩展为 publish 接口
        interfaceService.updateStatus(id, 1);
        return Result.success();
    }
}
