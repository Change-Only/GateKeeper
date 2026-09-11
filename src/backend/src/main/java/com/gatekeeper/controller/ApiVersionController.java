package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.ApiVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 接口版本管理 Controller — T03b 接口生命周期子资源之一
 *
 * <p>接口路径（T03b）：
 * <ul>
 *   <li>GET    /api-version/list                      按 apiId 筛选</li>
 *   <li>GET    /api-version/current                  当前默认版本（is_current=1）</li>
 *   <li>GET    /api-version/{id}                      详情</li>
 *   <li>POST   /api-version/create                    创建（{@code api_version:create} 高危）</li>
 *   <li>POST   /api-version/{id}/set-current          设为当前版本（事务，EXACTLY ONE）</li>
 *   <li>POST   /api-version/{id}/deprecate            弃用（status=2）</li>
 *   <li>POST   /api-version/{id}/offline              下线（status=3）</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@RestController
@RequestMapping("/api-version")
@RequiredArgsConstructor
@Tag(name = "接口版本", description = "接口版本管理（多版本 + 当前默认 + 灰度 + 弃用/下线）")
public class ApiVersionController {

    private final ApiVersionService apiVersionService;

    /**
     * 按接口ID筛选版本列表。
     */
    @Operation(summary = "查询接口版本列表")
    @GetMapping("/list")
    public Result<List<ApiVersionDto>> list(@RequestParam(required = false) Long apiId) {
        return Result.success(apiVersionService.list(apiId));
    }

    /**
     * 当前默认版本。
     */
    @Operation(summary = "查询当前默认版本")
    @GetMapping("/current")
    public Result<ApiVersionDto> current(@RequestParam Long apiId) {
        return Result.success(apiVersionService.current(apiId));
    }

    /**
     * 版本详情。
     */
    @Operation(summary = "接口版本详情")
    @GetMapping("/{id}")
    public Result<ApiVersionDto> detail(@PathVariable Long id) {
        return Result.success(apiVersionService.get(id));
    }

    /**
     * 创建接口版本（高危）。
     *
     * <p>T03b 权限点 {@code api_version:create}（risk=true）。</p>
     */
    @RequirePerm(value = "api_version:create", risk = true)
    @Operation(summary = "创建接口版本")
    @PostMapping("/create")
    public Result<ApiVersionDto> create(@Valid @RequestBody ApiVersionDto dto) {
        return Result.success(apiVersionService.create(dto));
    }

    /**
     * 设为当前默认版本（事务）。
     */
    @Operation(summary = "设为当前默认版本")
    @PostMapping("/{id}/set-current")
    public Result<Void> setCurrent(@PathVariable Long id) {
        apiVersionService.setCurrent(id);
        return Result.success();
    }

    /**
     * 弃用版本（status=2）。
     */
    @Operation(summary = "弃用接口版本")
    @PostMapping("/{id}/deprecate")
    public Result<Void> deprecate(@PathVariable Long id) {
        apiVersionService.deprecate(id);
        return Result.success();
    }

    /**
     * 下线版本（status=3）。
     */
    @Operation(summary = "下线接口版本")
    @PostMapping("/{id}/offline")
    public Result<Void> offline(@PathVariable Long id) {
        apiVersionService.offline(id);
        return Result.success();
    }
}
