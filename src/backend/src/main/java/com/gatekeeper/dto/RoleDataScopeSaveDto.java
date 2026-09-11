package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;

/**
 * 角色数据权限保存 DTO — perm-datascope 页面全量覆盖保存入参
 *
 * <p>PUT /api/role-data-scope/{roleId} 使用：先删除该角色旧范围，再批量插入 {@code scopes}。
 * {@code scopes} 为空列表表示「不限范围」（全量覆盖为空白）。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Data
public class RoleDataScopeSaveDto {

    /** 角色ID */
    @NotNull(message = "角色ID不能为空")
    private Long roleId;

    /** 数据范围集合（空列表=不限范围，全量覆盖旧数据） */
    @Valid
    private List<DataScopeItemDto> scopes = new ArrayList<>();
}
