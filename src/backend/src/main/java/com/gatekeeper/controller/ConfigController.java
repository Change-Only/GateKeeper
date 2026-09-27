package com.gatekeeper.controller;

import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.dto.SysConfigDto;
import com.gatekeeper.entity.SysConfig;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.ConfigService;
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

/**
 * 参数配置 Controller — T05 sys-config 参数配置对外能力
 *
 * <p>接口路径（context-path=/api）：
 * <ul>
 *   <li>GET    /api/config/list        分页查询</li>
 *   <li>GET    /api/config/all         全量（下拉）</li>
 *   <li>GET    /api/config/{id}        详情（sensitive=1 脱敏）</li>
 *   <li>POST   /api/config/create      新建（高危写，sys:config:update）</li>
 *   <li>PUT    /api/config/update      更新（高危写，sys:config:update）</li>
 *   <li>DELETE /api/config/{id}        删除（高危写，sys:config:update，built_in=1 拒绝）</li>
 * </ul></p>
 *
 * <p>权限点：仅高危写操作加 @RequirePerm；读操作不强制（页面可见性已由菜单 perm 控制）。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@RestController
@RequestMapping("/config")
@RequiredArgsConstructor
@Tag(name = "参数配置", description = "系统参数配置管理")
public class ConfigController {

    private final ConfigService configService;

    /**
     * 分页查询配置。
     */
    @Operation(summary = "分页查询配置")
    @GetMapping("/list")
    public Result<PageResult<SysConfig>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String configGroup,
            @RequestParam(required = false) Integer status) {
        return Result.success(configService.pageQuery(pageNum, pageSize, keyword, configGroup, status));
    }
    /**
     * 新建配置（高危写）。
     */
    @RequirePerm(value = "sys:config:update")
    @Operation(summary = "新建配置")
    @PostMapping("/create")
    public Result<SysConfig> create(@Valid @RequestBody SysConfigDto dto) {
        return Result.success(configService.createConfig(dto));
    }

    /**
     * 更新配置（高危写）。
     */
    @RequirePerm(value = "sys:config:update")
    @Operation(summary = "更新配置")
    @PutMapping("/update")
    public Result<Void> update(@Valid @RequestBody SysConfigDto dto) {
        configService.updateConfig(dto);
        return Result.success();
    }

    /**
     * 删除配置（高危写，built_in=1 拒绝）。
     */
    @RequirePerm(value = "sys:config:update")
    @Operation(summary = "删除配置")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        configService.deleteConfig(id);
        return Result.success();
    }
}
