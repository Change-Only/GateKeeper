package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.AppCredentialDto;
import com.gatekeeper.dto.CredentialRotateRequest;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.AppCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
 * 应用凭证管理 Controller — T03a 凭证主数据对外能力
 *
 * <p>接口路径（T03a）：
 * <ul>
 *   <li>GET    /api/app-credential/list                       按 app/env/status 筛选</li>
 *   <li>GET    /api/app-credential/{id}                       详情</li>
 *   <li>POST   /api/app-credential/create                     创建（{@code app_credential:create} 高危）</li>
 *   <li>POST   /api/app-credential/rotate                     灰度轮换（核心：零停机）</li>
 *   <li>POST   /api/app-credential/complete-rotate            完成轮换</li>
 *   <li>POST   /api/app-credential/{id}/revoke                立即吊销（{@code app:credential:revoke} 高危）</li>
 *   <li>PUT    /api/app-credential/{id}/update                修改 alias / expireTime</li>
 * </ul></p>
 *
 * <p>安全约束：除 create / rotate 响应外，<strong>任何接口绝不返回 appSecret 明文</strong>。</p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@RestController
@RequestMapping("/app-credential")
@RequiredArgsConstructor
@Tag(name = "应用凭证", description = "应用凭证 + 灰度轮换")
public class AppCredentialController {

    private final AppCredentialService appCredentialService;

    /**
     * 按 app/env/status 筛选凭证列表。
     *
     * <p>列表接口不返回明文 secret。</p>
     */
    @Operation(summary = "查询凭证列表")
    @GetMapping("/list")
    public Result<List<AppCredentialDto>> list(
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) String envCode,
            @RequestParam(required = false) Integer status) {
        return Result.success(appCredentialService.list(appId, envCode, status));
    }

    /**
     * 凭证详情。
     */
    @Operation(summary = "凭证详情")
    @GetMapping("/{id}")
    public Result<AppCredentialDto> detail(@PathVariable Long id) {
        return Result.success(appCredentialService.get(id));
    }

    /**
     * 创建凭证（高危）。
     *
     * <p>本接口是「明文返回窗口」之一。返回的 appSecret 仅此一次可读。</p>
     *
     * <p>T03a 权限点 {@code app_credential:create}（risk=1）。</p>
     */
    @RequirePerm(value = "app_credential:create", risk = true)
    @Operation(summary = "创建凭证")
    @PostMapping("/create")
    public Result<AppCredentialDto> create(@Valid @RequestBody AppCredentialDto dto) {
        return Result.success(appCredentialService.create(dto));
    }

    /**
     * 灰度轮换。
     *
     * <p>核心：业务规则同 {@link AppCredentialService#rotate}，
     * 响应是新凭证 DTO + appSecret 明文（仅此一次）。</p>
     */
    @Operation(summary = "灰度轮换（创建新凭证 + 旧凭证 7 天后吊销）")
    @RequirePerm(value = "app_credential:rotate", risk = true)
    @PostMapping("/rotate")
    public Result<AppCredentialDto> rotate(@Valid @RequestBody CredentialRotateRequest req) {
        return Result.success(appCredentialService.rotate(req));
    }

    /**
     * 完成轮换。
     *
     * <p>将 rotateFlag=1 凭证晋升为主密钥（rotateFlag=0），
     * 旧主密钥 status=3（已吊销）。</p>
     */
    @Operation(summary = "完成轮换")
    @RequirePerm(value = "app_credential:complete", risk = true)
    @PostMapping("/complete-rotate")
    public Result<Integer> completeRotate(@Valid @RequestBody CredentialRotateRequest req) {
        int n = appCredentialService.completeRotate(req.getAppId(), req.getEnvCode());
        return Result.success(n);
    }

    /**
     * 立即吊销凭证（高危）。
     *
     * <p>T02 已加 @RequirePerm {@code app:credential:revoke}（risk=1）。</p>
     */
    @RequirePerm(value = "app:credential:revoke", risk = true)
    @Operation(summary = "立即吊销凭证")
    @PostMapping("/{id}/revoke")
    public Result<Void> revoke(@PathVariable Long id) {
        appCredentialService.revoke(id);
        return Result.success();
    }

    /**
     * 修改凭证别名 / 过期时间（不影响密钥本身）。
     */
    @Operation(summary = "修改凭证别名/过期时间")
    @RequirePerm(value = "app_credential:update", risk = true)
    @PutMapping("/{id}/update")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AppCredentialDto dto) {
        appCredentialService.update(id, dto);
        return Result.success();
    }
}
