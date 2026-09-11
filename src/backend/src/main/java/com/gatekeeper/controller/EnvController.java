package com.gatekeeper.controller;

import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.dto.EnvDto;
import com.gatekeeper.entity.Env;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.EnvService;
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
 * 环境管理 Controller — T03a 环境主数据对外能力
 *
 * <p>接口路径（T03a）：
 * <ul>
 *   <li>GET    /api/env/list           分页查询</li>
 *   <li>GET    /api/env/all            全量（启用）—— 下拉用</li>
 *   <li>GET    /api/env/{id}           详情</li>
 *   <li>POST   /api/env/create         新建</li>
 *   <li>PUT    /api/env/update         更新（envCode 不可改）</li>
 *   <li>DELETE /api/env/{id}           删除（{@code env:delete} 高危）</li>
 * </ul></p>
 *
 * <p>架构 D1：env_code 一旦创建不可修改（service 层校验）。</p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@RestController
@RequestMapping("/env")
@RequiredArgsConstructor
@Tag(name = "环境管理", description = "环境与网关主数据")
public class EnvController {

    private final EnvService envService;

    /**
     * 分页查询环境。
     */
    @Operation(summary = "分页查询环境")
    @GetMapping("/list")
    public Result<PageResult<EnvDto>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String envCode,
            @RequestParam(required = false) Integer status) {
        return Result.success(envService.pageQuery(pageNum, pageSize, envCode, status));
    }

    /**
     * 全量启用环境（下拉用）。
     *
     * <p>前端每个表单都查，按 sortOrder 升序。</p>
     */
    @Operation(summary = "全量启用环境（下拉）")
    @GetMapping("/all")
    public Result<List<EnvDto>> all() {
        return Result.success(envService.listEnabled());
    }

    /**
     * 环境详情。
     */
    @Operation(summary = "环境详情")
    @GetMapping("/{id}")
    public Result<Env> detail(@PathVariable Long id) {
        Env env = envService.getEnv(id);
        return env == null ? Result.notFound("环境不存在") : Result.success(env);
    }

    /**
     * 新建环境。
     */
    @Operation(summary = "新建环境")
    @PostMapping("/create")
    public Result<Env> create(@Valid @RequestBody EnvDto dto) {
        return Result.success(envService.createEnv(dto));
    }

    /**
     * 更新环境（envCode 不可改，架构 D1）。
     */
    @Operation(summary = "更新环境")
    @PutMapping("/update")
    public Result<Void> update(@Valid @RequestBody EnvDto dto) {
        envService.updateEnv(dto.getId(), dto);
        return Result.success();
    }

    /**
     * 删除环境（高危）。
     *
     * <p>T03a 权限点 {@code env:delete}（risk=1）。</p>
     */
    @RequirePerm(value = "env:delete", risk = true)
    @Operation(summary = "删除环境")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        envService.deleteEnv(id);
        return Result.success();
    }
}
