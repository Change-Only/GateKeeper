package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.SysEncryptionConfig;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.SysEncryptionConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 平台级加解密总开关 Controller — T16-1
 *
 * <p>接口路径前缀 {@code /sys/encryption-config}：
 * <ul>
 *   <li>GET /sys/encryption-config   读取总开关（缺行返回 enabled=1 的虚拟行）</li>
 *   <li>PUT /sys/encryption-config   更新总开关（{@code sys:security:update} 高危）</li>
 * </ul></p>
 *
 * <p><b>权限口径（刻意的选择，勿随手"修正"）</b>：
 * <ul>
 *   <li>GET <b>不加</b> {@code @RequirePerm} —— 与本仓其余只读端点一致
 *       （{@code /app/list}、{@code /sys/ip-whitelist} 等均无注解），
 *       只读 + 页面菜单可见性即为控制；单点加注解会让缺该码的角色一打开页面就 403。</li>
 *   <li>PUT 复用既有的 {@code sys:security:update}（该码属「系统设置 → 安全策略」语义范畴，
 *       且已播种、已授权）—— 避免"新增码忘了授权 ⇒ 连 SUPER_ADMIN 都拿不到"的历史坑
 *       （详见 docs/CONTRACTS §16.2）。</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T16-1 (2026-09-15)
 */
@RestController
@RequestMapping("/sys/encryption-config")
@RequiredArgsConstructor
@Tag(name = "平台加解密总开关", description = "网关级加解密总闸：关闭即全平台强制明文")
public class SysEncryptionConfigController {

    private final SysEncryptionConfigService service;

    /** 读取平台加解密总开关（缺行=启用） */
    @Operation(summary = "查询平台加解密总开关")
    @GetMapping
    public Result<SysEncryptionConfig> get() {
        return Result.success(service.get());
    }

    /**
     * 更新平台加解密总开关。
     *
     * <p>标 {@code risk = true}：把它置 0 会<b>立刻把全平台的接口加解密降级为明文</b>，
     * 属高危操作，必须强制审计。</p>
     *
     * @param body    至少含 {@code enabled}（1/0），可选 {@code remark}
     * @param request 用于取 {@code X-USER-ID}（由 {@code JwtAuthInterceptor} 写入）
     */
    @Operation(summary = "更新平台加解密总开关")
    @RequirePerm(value = "sys:security:update", risk = true)
    @PutMapping
    public Result<SysEncryptionConfig> update(@RequestBody SysEncryptionConfig body,
                                             HttpServletRequest request) {
        Long uid = resolveUid(request);
        return Result.success(service.updateEnabled(body.getEnabled(), uid, body.getRemark()));
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
