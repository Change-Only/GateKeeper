package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.ApiChangeLogDto;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.ApiChangeLogService;
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
 * 接口变更历史管理 Controller — T03b 接口生命周期子资源之一（追加型，只读）
 *
 * <p>接口路径（T03b）：
 * <ul>
 *   <li>GET    /api-change-log/list            按 apiId/changeType 筛选</li>
 *   <li>GET    /api-change-log/{id}            详情</li>
 *   <li>POST   /api-change-log/append          追加一条变更记录（{@code api_change_log:append} 高危）</li>
 * </ul></p>
 *
 * <p>约束：变更历史为审计凭证，<strong>仅追加</strong>，无 update / delete 端点。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@RestController
@RequestMapping("/api-change-log")
@RequiredArgsConstructor
@Tag(name = "接口变更历史", description = "接口变更历史（追加型审计，CREATE/UPDATE/PUBLISH/OFFLINE/DELETE）")
public class ApiChangeLogController {

    private final ApiChangeLogService apiChangeLogService;

    /**
     * 按接口ID / 变更类型筛选变更历史列表。
     */
    @Operation(summary = "查询接口变更历史列表")
    @GetMapping("/list")
    public Result<List<ApiChangeLogDto>> list(
            @RequestParam(required = false) Long apiId,
            @RequestParam(required = false) String changeType) {
        return Result.success(apiChangeLogService.list(apiId, changeType));
    }

    /**
     * 变更历史详情。
     */
    @Operation(summary = "接口变更历史详情")
    @GetMapping("/{id}")
    public Result<ApiChangeLogDto> detail(@PathVariable Long id) {
        return Result.success(apiChangeLogService.get(id));
    }

    /**
     * 追加一条变更记录（高危）。
     *
     * <p>createTime 由服务端填充（now）。T03b 权限点 {@code api_change_log:append}（risk=true）。</p>
     */
    @RequirePerm(value = "api_change_log:append", risk = true)
    @Operation(summary = "追加接口变更记录")
    @PostMapping("/append")
    public Result<ApiChangeLogDto> append(@Valid @RequestBody ApiChangeLogDto dto) {
        return Result.success(apiChangeLogService.append(dto));
    }
}
