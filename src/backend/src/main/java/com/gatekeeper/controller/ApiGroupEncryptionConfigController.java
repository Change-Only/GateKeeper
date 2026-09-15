package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.ApiGroupEncryptionConfig;
import com.gatekeeper.gateway.dto.EffectiveGroupEncryption;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.ApiGroupEncryptionConfigService;
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

/**
 * 接口分组加解密配置 Controller — T15-1
 *
 * <p>接口路径前缀 {@code /api-group-encryption}：
 * <ul>
 *   <li>GET    /effective?groupId=   分组生效配置（含沿分组树向上继承；无配置返回 data=null）</li>
 *   <li>GET    /own?groupId=         本分组自己的配置行（编辑回填用）</li>
 *   <li>POST   /upsert               新建/覆盖（{@code api_group_encryption:create} 高危）</li>
 *   <li>PUT    /{id}                 按行 ID 更新（{@code api_group_encryption:update} 高危）</li>
 *   <li>DELETE /{id}                 清除（{@code api_group_encryption:delete} 高危）</li>
 * </ul></p>
 *
 * <p>两个查询端点**不加** {@code @RequirePerm}：它们只读且回显密钥，
 * 但运维/审计角色也需要看到"这个分组到底加不加密"。写端点全部挂高危权限点。</p>
 *
 * @author GateKeeper
 * @since T15-1 (2026-09-15)
 */
@RestController
@RequestMapping("/api-group-encryption")
@RequiredArgsConstructor
@Tag(name = "分组加解密配置", description = "接口分组加解密配置（分组树向上继承）")
public class ApiGroupEncryptionConfigController {

    private final ApiGroupEncryptionConfigService service;

    /** 查询分组生效加解密配置（前端"生效预览"用，与网关口径一致） */
    @Operation(summary = "查询分组生效加解密配置")
    @GetMapping("/effective")
    public Result<EffectiveGroupEncryption> effective(@RequestParam Long groupId) {
        return Result.success(service.getEffective(groupId));
    }

    /** 查询本分组自己的配置行（不含继承），供编辑回填 */
    @Operation(summary = "查询本分组加解密配置")
    @GetMapping("/own")
    public Result<ApiGroupEncryptionConfig> own(@RequestParam Long groupId) {
        return Result.success(service.getOwn(groupId));
    }

    /** 新建 / 覆盖本分组配置 */
    @RequirePerm(value = "api_group_encryption:create", risk = true)
    @Operation(summary = "保存分组加解密配置")
    @PostMapping("/upsert")
    public Result<Void> upsert(@RequestParam Long groupId,
                               @RequestBody ApiGroupEncryptionConfig config) {
        service.upsert(groupId, config);
        return Result.success();
    }

    /** 按行 ID 更新 */
    @RequirePerm(value = "api_group_encryption:update", risk = true)
    @Operation(summary = "更新分组加解密配置")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id,
                               @RequestBody ApiGroupEncryptionConfig config) {
        service.updateById(id, config);
        return Result.success();
    }

    /** 清除本分组配置（回落父级继承） */
    @RequirePerm(value = "api_group_encryption:delete", risk = true)
    @Operation(summary = "清除分组加解密配置")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return Result.success();
    }
}
