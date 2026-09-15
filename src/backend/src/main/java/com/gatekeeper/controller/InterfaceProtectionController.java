package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.InterfaceVisibilityVo;
import com.gatekeeper.entity.SysInterfaceCryptoConfig;
import com.gatekeeper.entity.SysInterfaceVisibility;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.InterfaceVisibilityService;
import com.gatekeeper.service.SysInterfaceCryptoConfigService;
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

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * 接口信息保护 Controller — T17
 *
 * <p>接口路径前缀 {@code /sys/interface-protection}（配置面，非控制台数据面）：
 * <ul>
 *   <li>GET    /sys/interface-protection/config            读取加密开关（缺行返回 enabled=1 虚拟行）</li>
 *   <li>PUT    /sys/interface-protection/config            更新加密开关</li>
 *   <li>GET    /sys/interface-protection/whitelist         可见性白名单列表（含停用条目）</li>
 *   <li>POST   /sys/interface-protection/whitelist         新增白名单</li>
 *   <li>PUT    /sys/interface-protection/whitelist/{id}    更新（含启用/停用）</li>
 *   <li>DELETE /sys/interface-protection/whitelist/{id}    删除</li>
 *   <li>GET    /sys/interface-protection/subject-options   候选用户 / 角色下拉</li>
 * </ul></p>
 *
 * <p><b>权限口径（刻意的选择，勿随手"修正"）</b>：
 * <ul>
 *   <li>GET <b>不加</b> {@code @RequirePerm} —— 与本仓其余只读端点一致
 *       （{@code /app/list}、{@code /sys/ip-whitelist}、{@code /sys/interface-protection/whitelist} 均无注解），
 *       只读 + 页面菜单可见性即为控制；单点加注解会让缺该码的角色一打开页面就 403。</li>
 *   <li>三个写端点复用既有的 {@code sys:security:update}（在「系统设置 → 安全策略」语义范畴内，
 *       该页既有 CRUD 也用同一码）—— <b>零新增权限点</b>，避免"新增码忘了授权
 *       ⇒ 连 SUPER_ADMIN 都拿不到"的历史坑（详见 docs/CONTRACTS §16.2 / §18）。</li>
 * </ul>
 * 三个写端点均标 {@code risk = true}：它们直接改变「谁能看到接口明文」，
 * 尤其把开关从 1 改成 0 会<b>让全平台接口路径与参数立即对所有人可见</b>，属高危。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
@RestController
@RequestMapping("/sys/interface-protection")
@RequiredArgsConstructor
@Tag(name = "接口信息保护", description = "平台自身接口信息的存储加密开关与可见性白名单")
public class InterfaceProtectionController {

    private final SysInterfaceCryptoConfigService configService;
    private final InterfaceVisibilityService visibilityService;

    // =====================================================================
    // 开关
    // =====================================================================

    /** 读取接口信息加密开关（缺行 = 启用） */
    @Operation(summary = "查询接口信息加密开关")
    @GetMapping("/config")
    public Result<SysInterfaceCryptoConfig> getConfig() {
        return Result.success(configService.get());
    }

    /**
     * 更新接口信息加密开关。
     *
     * @param body    至少含 {@code enabled}（1/0），可选 {@code remark}
     * @param request 用于取 {@code X-USER-ID}（由 {@code JwtAuthInterceptor} 写入）
     */
    @Operation(summary = "更新接口信息加密开关")
    @RequirePerm(value = "sys:security:update", risk = true)
    @PutMapping("/config")
    public Result<SysInterfaceCryptoConfig> updateConfig(@RequestBody SysInterfaceCryptoConfig body,
                                                         HttpServletRequest request) {
        return Result.success(configService.updateEnabled(
                body.getEnabled(), resolveUid(request), body.getRemark()));
    }

    // =====================================================================
    // 可见性白名单
    // =====================================================================

    /** 查询全部白名单条目（启用在前，含主体名称） */
    @Operation(summary = "查询接口信息可见性白名单")
    @GetMapping("/whitelist")
    public Result<List<InterfaceVisibilityVo>> list() {
        return Result.success(visibilityService.list());
    }

    /** 新增白名单 */
    @Operation(summary = "新增接口信息可见性白名单")
    @RequirePerm(value = "sys:security:update", risk = true)
    @PostMapping("/whitelist")
    public Result<Void> add(@RequestBody SysInterfaceVisibility entry) {
        visibilityService.add(entry);
        return Result.success();
    }

    /** 更新白名单（含启用/停用） */
    @Operation(summary = "更新接口信息可见性白名单")
    @RequirePerm(value = "sys:security:update", risk = true)
    @PutMapping("/whitelist/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysInterfaceVisibility entry) {
        visibilityService.update(id, entry);
        return Result.success();
    }

    /** 删除白名单 */
    @Operation(summary = "删除接口信息可见性白名单")
    @RequirePerm(value = "sys:security:update", risk = true)
    @DeleteMapping("/whitelist/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        visibilityService.delete(id);
        return Result.success();
    }

    /** 候选主体下拉（用户 / 角色） */
    @Operation(summary = "白名单候选主体下拉")
    @GetMapping("/subject-options")
    public Result<Map<String, Object>> subjectOptions() {
        return Result.success(visibilityService.subjectOptions());
    }

    /** 从请求属性解析操作人 id（兼容 Long/Integer/String，与本仓既有做法一致） */
    private Long resolveUid(HttpServletRequest request) {
        Object v = request.getAttribute("X-USER-ID");
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
