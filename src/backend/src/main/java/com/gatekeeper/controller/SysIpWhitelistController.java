package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.SysIpWhitelist;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.SysIpWhitelistService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统级访问白名单 Controller — T15-4
 *
 * <p>接口路径前缀 {@code /sys/ip-whitelist}：
 * <ul>
 *   <li>GET    /sys/ip-whitelist        列表（含停用条目，供管理页展示）</li>
 *   <li>POST   /sys/ip-whitelist        新增</li>
 *   <li>PUT    /sys/ip-whitelist/{id}   更新（含启用/停用）</li>
 *   <li>DELETE /sys/ip-whitelist/{id}   删除</li>
 * </ul></p>
 *
 * <p><b>权限口径（刻意的选择，勿随手"修正"）</b>：
 * <ul>
 *   <li>列表端点**不加** {@code @RequirePerm} —— 与本仓其余只读端点一致
 *       （{@code /app/list}、{@code /app/{id}/ip-whitelist}、{@code /app/{id}/rate-limit} 均无注解）。
 *       只读 + 仅菜单可见性控制属既定豁免，单点加注解反而会让缺该码的角色点开页面就 403。</li>
 *   <li>写端点复用既有的 {@code sys:security:update}（在「系统设置 → 安全策略」语义范畴内，
 *       该页既有 CRUD 也用同一码）。复用已播种已授权的码，避免"新增码忘了授权
 *       ⇒ 连 SUPER_ADMIN 都拿不到"的历史坑。</li>
 * </ul></p>
 *
 * <p><b>改动此配置会立刻改变网关准入</b>，故三个写端点均标 {@code risk = true}（强制审计）。</p>
 *
 * @author GateKeeper
 * @since T15-4 (2026-09-15)
 */
@RestController
@RequestMapping("/sys/ip-whitelist")
@RequiredArgsConstructor
@Tag(name = "系统访问白名单", description = "网关入口全局访问白名单（表空=不限制）")
public class SysIpWhitelistController {

    private final SysIpWhitelistService service;

    /** 查询全部条目（启用在前） */
    @Operation(summary = "查询系统访问白名单")
    @GetMapping
    public Result<List<SysIpWhitelist>> list() {
        return Result.success(service.list());
    }

    /** 新增一条白名单 */
    @Operation(summary = "新增系统访问白名单")
    @RequirePerm(value = "sys:security:update", risk = true)
    @PostMapping
    public Result<Void> add(@RequestBody SysIpWhitelist entry) {
        service.add(entry);
        return Result.success();
    }

    /** 更新（含启用/停用） */
    @Operation(summary = "更新系统访问白名单")
    @RequirePerm(value = "sys:security:update", risk = true)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysIpWhitelist entry) {
        service.updateById(id, entry);
        return Result.success();
    }

    /** 删除 */
    @Operation(summary = "删除系统访问白名单")
    @RequirePerm(value = "sys:security:update", risk = true)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return Result.success();
    }
}
