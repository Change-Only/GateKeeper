package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.ApiEncryptionConfig;
import com.gatekeeper.entity.AppEncryptionConfig;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.ApiEncryptionConfigService;
import com.gatekeeper.service.AppEncryptionConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 加解密配置管理 Controller（接口级 + 应用级）
 *
 * <p>负责请求/响应加解密相关配置的管理，分为两个维度：
 * 接口级（ApiEncryptionConfig）与应用级（AppEncryptionConfig）加解密配置的查询与保存。
 *
 * <p>主要接口路径前缀：{@code /encryption}
 * <ul>
 *   <li>GET/POST /encryption/interface    查询/保存接口级加解密配置</li>
 *   <li>GET/POST /encryption/app          查询/保存应用级加解密配置</li>
 * </ul>
 */
@RestController
@RequestMapping("/encryption")
@RequiredArgsConstructor
@Tag(name = "加密配置", description = "加密配置管理接口")
public class EncryptionConfigController {

    private final ApiEncryptionConfigService apiEncryptionConfigService;
    private final AppEncryptionConfigService appEncryptionConfigService;

    // === 接口级加解密配置 ===

    /**
     * 查询指定接口的加解密配置
     *
     * @param interfaceId 接口 ID
     * @return 该接口对应的加解密配置（加密算法、密钥等）
     */
    @Operation(summary = "查询接口加密配置")
    @GetMapping("/interface/{interfaceId}")
    public Result<ApiEncryptionConfig> getInterfaceConfig(@PathVariable Long interfaceId) {
        return Result.success(apiEncryptionConfigService.getByInterfaceId(interfaceId));
    }

    /**
     * 保存接口级加解密配置
     *
     * <p>P0-4：请求体直接反序列化为配置并落库（含算法/密钥/开关），改写后可使报文明文传输或
     * 写入攻击者已知的密钥 ⇒ 补 {@code @RequirePerm}。复用同域既有的 {@code api:env:update}
     * （零新增码；该码本就是「配置环境」类写权限，同码不同 risk 是既存设计）。</p>
     *
     * @param config 接口级加解密配置实体
     * @return 操作结果（无业务数据返回）
     */
    @RequirePerm(value = "api:env:update", risk = true)
    @Operation(summary = "保存接口加密配置")
    @PostMapping("/interface")
    public Result<Void> saveInterfaceConfig(@RequestBody ApiEncryptionConfig config) {
        apiEncryptionConfigService.saveConfig(config);
        return Result.success();
    }

    // === 应用级加解密配置 ===

    /**
     * 查询指定应用的加解密配置
     *
     * @param appId 应用 ID
     * @return 该应用对应的加解密配置（加密算法、密钥等）
     */
    @Operation(summary = "查询应用加密配置")
    @GetMapping("/app/{appId}")
    public Result<AppEncryptionConfig> getAppConfig(@PathVariable Long appId) {
        return Result.success(appEncryptionConfigService.getByAppId(appId));
    }

    /**
     * 保存应用级加解密配置
     *
     * <p>P0-4：同 {@link #saveInterfaceConfig}，补同一个 {@code api:env:update} 权限点。</p>
     *
     * @param config 应用级加解密配置实体
     * @return 操作结果（无业务数据返回）
     */
    @RequirePerm(value = "api:env:update", risk = true)
    @Operation(summary = "保存应用加密配置")
    @PostMapping("/app")
    public Result<Void> saveAppConfig(@RequestBody AppEncryptionConfig config) {
        appEncryptionConfigService.saveConfig(config);
        return Result.success();
    }
}
