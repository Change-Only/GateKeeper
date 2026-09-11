package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.RoleDataScopeSaveDto;
import com.gatekeeper.entity.SysRoleDataScope;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.RoleDataScopeService;
import com.gatekeeper.vo.DataScopeOptionsVo;
import com.gatekeeper.vo.RoleSimpleVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 数据权限 Controller — T05 perm-datascope 数据权限对外能力
 *
 * <p>接口路径（context-path=/api，后端无模块前缀，对齐 /env / notify-channel 风格）：
 * <ul>
 *   <li>GET  /api/role-data-scope/roles        角色选择器（sys:datascope:view）</li>
 *   <li>GET  /api/role-data-scope/options       范围值选项：业务线/环境/接口分组（sys:datascope:view）</li>
 *   <li>GET  /api/role-data-scope/{roleId}      某角色当前范围（空=不限）（sys:datascope:view）</li>
 *   <li>PUT  /api/role-data-scope/{roleId}      全量覆盖保存（高危写，sys:datascope:update, risk=true）</li>
 * </ul></p>
 *
 * <p>语义：sys_role_datascope 空表 = 该角色「不限范围」。PUT 为全量覆盖（先删后插）。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@RestController
@RequestMapping("/role-data-scope")
@RequiredArgsConstructor
@Tag(name = "数据权限", description = "角色数据范围配置")
public class DataScopeController {

    private final RoleDataScopeService roleDataScopeService;

    /**
     * 角色选择器列表。
     */
    @Operation(summary = "角色选择器")
    @GetMapping("/roles")
    public Result<List<RoleSimpleVo>> roles(@RequestParam(required = false) String keyword) {
        return Result.success(roleDataScopeService.listRolesForSelector(keyword));
    }

    /**
     * 数据范围选项（业务线/环境/接口分组）。
     */
    @Operation(summary = "数据范围选项")
    @GetMapping("/options")
    public Result<DataScopeOptionsVo> options() {
        return Result.success(roleDataScopeService.listOptions());
    }

    /**
     * 某角色当前数据范围（空=不限）。
     */
    @Operation(summary = "角色数据范围")
    @GetMapping("/{roleId}")
    public Result<List<SysRoleDataScope>> detail(@PathVariable Long roleId) {
        return Result.success(roleDataScopeService.listByRole(roleId));
    }

    /**
     * 保存角色数据范围（全量覆盖，高危写）。
     */
    @RequirePerm(value = "sys:datascope:update", risk = true)
    @Operation(summary = "保存角色数据范围（全量覆盖）")
    @PutMapping("/{roleId}")
    public Result<Void> save(@PathVariable Long roleId, @Valid @RequestBody RoleDataScopeSaveDto dto) {
        if (dto == null) {
            dto = new RoleDataScopeSaveDto();
        }
        dto.setRoleId(roleId);
        roleDataScopeService.saveRoleScopes(dto);
        return Result.success();
    }
}
