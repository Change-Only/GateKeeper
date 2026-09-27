package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiEnvConfigDto;
import com.gatekeeper.service.ApiEnvConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 接口环境配置管理 Controller — T03b 接口生命周期子资源之一
 *
 * <p>接口路径（T03b）：
 * <ul>
 *   <li>GET    /api-env-config/list                      按 apiId/envCode 筛选</li>
 *   <li>GET    /api-env-config/{id}                      详情</li>
 *   <li>POST   /api-env-config/create                    创建（{@code api_env_config:create} 高危）</li>
 *   <li>POST   /api-env-config/upsert                    创建或更新（按 apiId+envCode+version）</li>
 *   <li>PUT    /api-env-config/{id}/update               更新可编辑字段</li>
 *   <li>POST   /api-env-config/{id}/test                 连通性测试（HEAD，通过置已验证）</li>
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
}
