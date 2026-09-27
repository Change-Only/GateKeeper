package com.gatekeeper.controller;

import com.gatekeeper.common.Result;
import com.gatekeeper.dto.AppCredentialDto;
import com.gatekeeper.dto.CredentialRotateRequest;
import com.gatekeeper.security.AccountPasswordVerifier;
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

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;
import java.util.Map;

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
 *   <li>POST   /api/app-credential/{id}/reveal                查看密钥明文（T14：需当前账号密码二次确认）</li>
 * </ul></p>
 *
 * <p>安全约束：明文 appSecret 只在三个窗口出现 —— create / rotate / <b>reveal（须密码二次确认）</b>；
 * 其余任何接口绝不返回明文。</p>
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

    /** T14 二次查看密钥的「当前账号密码」闸门 */
    private final AccountPasswordVerifier passwordVerifier;

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

    /**
     * 二次查看密钥明文（T14 新增）。
     *
     * <p>这是除 create / rotate 之外的<b>第三个明文返回窗口</b>，因此设了两道闸：</p>
     * <ol>
     *   <li><b>权限闸</b>：{@code @RequirePerm("app_credential:rotate", risk = true)}。
     *       复用 rotate 而非新开权限点，理由是<b>信息暴露面完全相同</b>——rotate 的响应里本来
     *       就带一份崭新的明文 secret，所以「能 rotate 的人」本来就能拿到明文。若给 reveal 配一个
     *       更弱的码，等于<b>降低</b>了拿到明文的门槛；反之给更强/新码，又要额外播种
     *       {@code sys_menu} + {@code sys_role_menu} 并清权限缓存，收益为零。
     *       <b>若将来想把二者拆开</b>（例如允许「只读密钥」角色），需新增
     *       {@code app_credential:reveal} 权限点并照抄 rotate 的持有角色集合补授权。</li>
     *   <li><b>身份闸</b>：必须提交当前登录账号的密码（{@link AccountPasswordVerifier}），
     *       防止「有人趁管理员没锁屏顺手看一眼」。</li>
     * </ol>
     *
     * <p>⚠️ 密码错误一律回 {@code code=400}（而非 401）：前端 {@code api/index.js} 把 HTTP 401
     * 当作登录过期处理（清 token + 跳登录页），密码打错一次就登出是不能接受的。</p>
     *
     * @param id      凭证 ID
     * @param body    {@code {"password": "当前登录账号的密码"}}
     * @param request 用于取 {@code X-USER-ID}（由 {@code JwtAuthInterceptor} 写入）
     * @return 含明文 {@code appSecret} 的凭证 DTO
     */
    @RequirePerm(value = "app_credential:rotate", risk = true)
    @Operation(summary = "查看密钥明文（需当前账号密码二次确认）")
    @PostMapping("/{id}/reveal")
    public Result<AppCredentialDto> reveal(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, String> body,
                                          HttpServletRequest request) {
        Long uid = passwordVerifier.currentUid(request);
        String password = body == null ? null : body.get("password");
        String err = passwordVerifier.check(uid, password);
        if (err != null) {
            return AccountPasswordVerifier.ERR_NOT_LOGIN.equals(err)
                    ? Result.error(401, err)
                    : Result.error(400, err);
        }
        return Result.success(appCredentialService.reveal(id));
    }
}
