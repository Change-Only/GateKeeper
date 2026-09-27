package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiGroupEnvConfigDto;
import com.gatekeeper.gateway.dto.EffectiveEnvConfig;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.ApiGroupEnvConfigService;
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
 * 接口分组环境配置 Controller — T13
 *
 * <p>接口路径：
 * <ul>
 *   <li>GET    /api-group-env-config/list?groupId=&amp;envCode=  本分组自己的配置（裸数组）</li>
 *   <li>GET    /api-group-env-config/effective?groupId=         4 个环境的**生效配置**（含继承来源）</li>
 *   <li>GET    /api-group-env-config/{id}                       详情</li>
 *   <li>POST   /api-group-env-config/upsert                     建或改（按 groupId+envCode 唯一）</li>
 *   <li>PUT    /api-group-env-config/{id}/update                更新可编辑字段</li>
 *   <li>POST   /api-group-env-config/{id}/test                  连通性测试</li>
 *   <li>POST   /api-group-env-config/{id}/toggle-mock           翻转 Mock 开关</li>
 *   <li>DELETE /api-group-env-config/{id}                       删除</li>
 * </ul></p>
 *
 * <p>权限口径：读取（list/effective/详情）与既有 /api-env-config 保持一致**不加注解**（只读、无独立权限点，
 * 属「只读 + 仅菜单可见性」豁免）；写操作全部挂显式权限点，其中 create/update/delete 标 risk=true
 * （改上游地址与 Mock 开关直接影响线上流量与返回内容）。</p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@RestController
@RequestMapping("/api-group-env-config")
@RequiredArgsConstructor
@Tag(name = "接口分组环境配置", description = "环境配置（服务前缀/超时/重试/Mock）按分组维护，分组树向上继承")
public class ApiGroupEnvConfigController {

    private final ApiGroupEnvConfigService apiGroupEnvConfigService;
    /**
     * 某分组在 4 个环境下的生效配置（含"继承自哪个分组"）。
     */
    @Operation(summary = "查询分组生效环境配置（含继承来源）")
    @GetMapping("/effective")
    public Result<List<EffectiveEnvConfig>> effective(@RequestParam Long groupId) {
        return Result.success(apiGroupEnvConfigService.effective(groupId));
    }
    /**
     * 建或改（按 groupId + envCode 唯一匹配；命中更新、未命中插入）。
     */
    @RequirePerm(value = "api_group_env_config:create", risk = true)
    @Operation(summary = "新增或更新分组环境配置")
    @PostMapping("/upsert")
    public Result<ApiGroupEnvConfigDto> upsert(@Valid @RequestBody ApiGroupEnvConfigDto dto) {
        return Result.success(apiGroupEnvConfigService.upsert(dto));
    }

    /**
     * 更新分组环境配置（仅可编辑字段）。
     */
    @RequirePerm(value = "api_group_env_config:update", risk = true)
    @Operation(summary = "更新分组环境配置")
    @PutMapping("/{id}/update")
    public Result<Void> update(@PathVariable Long id, @RequestBody ApiGroupEnvConfigDto dto) {
        apiGroupEnvConfigService.update(id, dto);
        return Result.success();
    }

    /**
     * 连通性测试：对服务前缀发起一次轻量探测。
     */
    @RequirePerm(value = "api_group_env_config:test", risk = false)
    @Operation(summary = "分组环境连通性测试")
    @PostMapping("/{id}/test")
    public Result<ApiGroupEnvConfigDto> test(@PathVariable Long id) {
        return Result.success(apiGroupEnvConfigService.testConnectivity(id));
    }

    /**
     * 翻转 Mock 开关（0↔1）。
     */
    @RequirePerm(value = "api_group_env_config:update", risk = false)
    @Operation(summary = "翻转分组环境 Mock 开关")
    @PostMapping("/{id}/toggle-mock")
    public Result<Void> toggleMock(@PathVariable Long id) {
        apiGroupEnvConfigService.toggleMock(id);
        return Result.success();
    }

    /**
     * 删除分组环境配置。
     */
    @RequirePerm(value = "api_group_env_config:delete", risk = true)
    @Operation(summary = "删除分组环境配置")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        apiGroupEnvConfigService.delete(id);
        return Result.success();
    }
}
