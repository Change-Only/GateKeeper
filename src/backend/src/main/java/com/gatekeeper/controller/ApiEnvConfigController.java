package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiEnvConfigDto;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.ApiEnvConfigService;
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
 * 接口环境配置管理 Controller — T03b 接口生命周期子资源之一
 *
 * <p>接口路径（T03b）：
 * <ul>
 *   <li>GET    /api-env-config/list                      按 apiId/envCode 筛选</li>
 *   <li>GET    /api-env-config/{id}                      详情</li>
 *   <li>POST   /api-env-config/create                    创建（{@code api_env_config:create} 高危）</li>
 *   <li>PUT    /api-env-config/{id}/update               更新可编辑字段</li>
 *   <li>POST   /api-env-config/{id}/toggle-mock          翻转 Mock 开关</li>
 *   <li>DELETE /api-env-config/{id}                      删除（{@code api_env_config:delete} 高危）</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@RestController
@RequestMapping("/api-env-config")
@RequiredArgsConstructor
@Tag(name = "接口环境配置", description = "接口环境配置（上游/Mock/超时/重试，支持灰度版本独立上游）")
public class ApiEnvConfigController {

    private final ApiEnvConfigService apiEnvConfigService;

    /**
     * 按接口ID / 环境编码筛选环境配置列表。
     */
    @Operation(summary = "查询接口环境配置列表")
    @GetMapping("/list")
    public Result<List<ApiEnvConfigDto>> list(
            @RequestParam(required = false) Long apiId,
            @RequestParam(required = false) String envCode) {
        return Result.success(apiEnvConfigService.list(apiId, envCode));
    }

    /**
     * 环境配置详情。
     */
    @Operation(summary = "接口环境配置详情")
    @GetMapping("/{id}")
    public Result<ApiEnvConfigDto> detail(@PathVariable Long id) {
        return Result.success(apiEnvConfigService.get(id));
    }

    /**
     * 创建接口环境配置（高危）。
     *
     * <p>T03b 权限点 {@code api_env_config:create}（risk=true）。</p>
     */
    @RequirePerm(value = "api_env_config:create", risk = true)
    @Operation(summary = "创建接口环境配置")
    @PostMapping("/create")
    public Result<ApiEnvConfigDto> create(@Valid @RequestBody ApiEnvConfigDto dto) {
        return Result.success(apiEnvConfigService.create(dto));
    }

    /**
     * 更新接口环境配置（仅可编辑字段）。
     */
    @Operation(summary = "更新接口环境配置")
    @PutMapping("/{id}/update")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ApiEnvConfigDto dto) {
        apiEnvConfigService.update(id, dto);
        return Result.success();
    }

    /**
     * 翻转 Mock 开关（0↔1）。
     */
    @Operation(summary = "翻转 Mock 开关")
    @PostMapping("/{id}/toggle-mock")
    public Result<Void> toggleMock(@PathVariable Long id) {
        apiEnvConfigService.toggleMock(id);
        return Result.success();
    }

    /**
     * 删除接口环境配置（高危）。
     *
     * <p>T03b 权限点 {@code api_env_config:delete}（risk=true）。</p>
     */
    @RequirePerm(value = "api_env_config:delete", risk = true)
    @Operation(summary = "删除接口环境配置")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        apiEnvConfigService.removeById(id);
        return Result.success();
    }
}
