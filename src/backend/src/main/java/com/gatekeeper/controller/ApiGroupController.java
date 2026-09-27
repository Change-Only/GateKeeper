package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.ApiGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 接口分组管理 Controller（多层级）
 *
 * <p>负责 API 接口分组（ApiGroup）的管理，业务模块包括：分组的增删改查、
 * 多层级树形结构的构建，以及查询指定分组下关联的接口列表。
 *
 * <p>主要接口路径前缀：{@code /group}
 * <ul>
 *   <li>GET    /group/list         平铺查询所有分组</li>
 *   <li>GET    /group/tree         查询多层级分组树</li>
 *   <li>GET    /group/{id}/interfaces 查询某分组下的接口列表</li>
 *   <li>POST   /group              新增分组</li>
 *   <li>PUT    /group/{id}         编辑分组</li>
 *   <li>DELETE /group/{id}         删除分组</li>
 * </ul>
 */
@RestController
@RequestMapping("/group")
@RequiredArgsConstructor
@Tag(name = "接口分组", description = "接口分组管理接口")
public class ApiGroupController {

    private final ApiGroupService apiGroupService;

    /**
     * 查询所有分组（平铺列表）
     *
     * @return 全部接口分组列表（不含层级关系）
     */
    @Operation(summary = "分页查询接口分组列表")
    @GetMapping("/list")
    public Result<List<ApiGroup>> list() {
        return Result.success(apiGroupService.listGroups());
    }

    /**
     * 查询多层级分组树
     *
     * @return 以树形结构组织的分组列表（含父子层级关系）
     */
    @Operation(summary = "查询分组树")
    @GetMapping("/tree")
    public Result<List<ApiGroup>> tree() {
        return Result.success(apiGroupService.listGroupTree());
    }
    /**
     * 新增接口分组
     *
     * @param group 分组实体（含分组名、父分组 ID 等）
     * @return 创建成功后的分组实体
     */
    @RequirePerm(value = "api_group:create", risk = true)
    @PostMapping
    public Result<ApiGroup> create(@RequestBody ApiGroup group) {
        return Result.success(apiGroupService.createGroup(group));
    }

    /**
     * 编辑接口分组
     *
     * @param id    分组 ID
     * @param group 待更新的分组信息
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "更新接口分组")
    @RequirePerm(value = "api_group:update", risk = true)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ApiGroup group) {
        apiGroupService.updateGroup(id, group);
        return Result.success();
    }

    /**
     * 删除接口分组
     *
     * @param id 分组 ID
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "删除接口分组")
    @RequirePerm(value = "api_group:delete", risk = true)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        apiGroupService.deleteGroup(id);
        return Result.success();
    }
}
