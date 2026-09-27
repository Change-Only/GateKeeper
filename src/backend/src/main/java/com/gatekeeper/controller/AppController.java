package com.gatekeeper.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.entity.App;
import com.gatekeeper.entity.AppIpWhitelist;
import com.gatekeeper.entity.AppRateLimit;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.AppInterfaceDocService;
import com.gatekeeper.service.AppService;
import com.gatekeeper.vo.AppInterfaceDocVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 应用管理 Controller
 *
 * <p>负责应用（App）的全生命周期管理，业务模块包括：应用的注册、编辑、启停、删除，
 * 以及应用关联的 IP 白名单维护、限流配置管理与密钥重置。</p>
 *
 * <p>主要接口路径前缀：{@code /app}
 * <ul>
 *   <li>GET  /app/list                    分页查询应用列表</li>
 *   <li>POST /app                         注册应用（{@code app:credential:create} 高危）</li>
 *   <li>PUT  /app/{id}                    编辑应用信息</li>
 *   <li>PUT  /app/{id}/status/{status}    启用/停用应用（{@code app:disable} 高危）</li>
 *   <li>DELETE /app/{id}                  删除应用</li>
 *   <li>GET/POST /app/{id}/ip-whitelist   查询/添加 IP 白名单</li>
 *   <li>DELETE /app/ip-whitelist/{id}     移除 IP 白名单</li>
 *   <li>GET/PUT /app/{id}/rate-limit      查询/更新限流配置</li>
 *   <li>POST /app/{id}/reset-secret       重置应用密钥（{@code app:credential:reset} 高危）</li>
 *   <li>GET  /app/{id}/interface-doc      该应用「有权限」的接口文档数据（T16-2）</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/app")
@RequiredArgsConstructor
@Tag(name = "应用", description = "应用管理接口")
public class AppController {

    private final AppService appService;

    /** T16-2：应用接口文档数据（只含有权限的接口） */
    private final AppInterfaceDocService appInterfaceDocService;

    /**
     * 分页查询应用列表
     *
     * @param current 当前页码（默认第 1 页）
     * @param size    每页条数（默认 10 条）
     * @param appName 应用名称（可选，模糊匹配）
     * @param status  应用状态（可选，用于筛选启用/停用）
     * @return 分页结果，包含应用列表及总数
     */
    @Operation(summary = "分页查询应用列表")
    @GetMapping("/list")
    public Result<PageResult<App>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String appName,
            @RequestParam(required = false) Integer status) {
        return Result.success(appService.pageQuery(current, size, appName, status));
    }

    /**
     * 查询该应用「有权限」的接口文档数据（T16-2）。
     *
     * <p>需求：「应用管理中增加接口文档导出，<b>只导出有权限的接口</b>」。</p>
     *
     * <p><b>「有权限」= 授权已生效（{@code status=1}）+ 在有效期内 + 接口已启用</b>；
     * 待审批/已驳回/已过期/已撤销的授权，以及已停用或已删除的接口，一律不出现在结果里。
     * 被剔除的两类分别计数返回（{@code danglingCount} / {@code disabledCount}），
     * 避免「导出条数 < 授权条数」变成无法解释的谜。</p>
     *
     * <p>本端点<b>刻意不加</b> {@code @RequirePerm}：与本仓其余只读端点
     * （{@code /app/list}、{@code /app/{id}/ip-whitelist} 等）保持一致 ——
     * 只读 + 页面菜单可见性即为控制，单点加注解会让缺该码的角色一打开页面就 403。
     * 数据本身只包含「该应用已被授权」的接口，不含任何密钥材料。</p>
     *
     * <p>只返回<b>数据</b>；Markdown 正文由前端 {@code utils/interfaceDoc.js} 生成
     * （与 T14 接入文档同一取舍：正文口径只在一处定义）。</p>
     *
     * @param id 应用 ID
     * @return 接口文档数据（应用不存在时 400）
     */
    @Operation(summary = "查询应用的接口文档数据")
    @GetMapping("/{id}/interface-doc")
    public Result<AppInterfaceDocVo> interfaceDoc(@PathVariable Long id) {
        return Result.success(appInterfaceDocService.build(id));
    }

    /**
     * 注册新应用（高危：会同时生成密钥）。
     *
     * <p>T08 换码修正：本端点的语义是「新建应用」，原先却用
     * {@code app:credential:create}（"新建凭证"）来保护，属码的语义错配 ——
     * 会导致「只授 app:create 的角色建不了应用」与「只授 app:credential:create
     * 的角色能建应用」两种错误。现改为 {@code app:create}。</p>
     *
     * <p>配套：种子 SQL 给 {@code EXTERNAL_PM} 补授 {@code app:create}，
     * 保证该角色原有能力零回归（它此前靠 {@code app:credential:create} 才能建应用）。</p>
     *
     * @param app 应用实体（含应用名、密钥等注册信息）
     * @return 创建成功后的应用实体（含生成的密钥等信息）
     */
    @RequirePerm(value = "app:create", risk = true)
    @PostMapping
    public Result<App> create(@RequestBody App app) {
        return Result.success(appService.createApp(app));
    }

    /**
     * 编辑应用信息
     *
     * @param id  应用 ID
     * @param app 待更新的应用信息
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "更新应用")
    @RequirePerm(value = "app:update", risk = true)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody App app) {
        appService.updateApp(id, app);
        return Result.success();
    }

    /**
     * 启用/停用应用（高危）
     *
     * <p>T02 权限点：{@code app:disable}</p>
     *
     * @param id     应用 ID
     * @param status 目标状态（如 1 启用、0 停用）
     * @return 操作结果（无业务数据返回）
     */
    @RequirePerm(value = "app:disable", risk = true)
    @Operation(summary = "更新应用状态")
    @PutMapping("/{id}/status/{status}")
    public Result<Void> updateStatus(@PathVariable Long id, @PathVariable Integer status) {
        appService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 删除应用
     *
     * @param id 应用 ID
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "删除应用")
    @RequirePerm(value = "app:delete", risk = true)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        appService.deleteApp(id);
        return Result.success();
    }

    /**
     * 查询应用的 IP 白名单列表
     *
     * @param id 应用 ID
     * @return 该应用已配置的 IP 白名单列表
     */
    @Operation(summary = "查询IP白名单")
    @GetMapping("/{id}/ip-whitelist")
    public Result<List<AppIpWhitelist>> listIpWhitelist(@PathVariable Long id) {
        return Result.success(appService.listIpWhitelist(id));
    }

    /**
     * 为应用添加一条 IP 白名单
     *
     * @param id        应用 ID
     * @param whitelist IP 白名单实体（含 IP 地址、备注等）
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "添加IP白名单")
    @RequirePerm(value = "app:ipwhitelist:add", risk = true)
    @PostMapping("/{id}/ip-whitelist")
    public Result<Void> addIpWhitelist(@PathVariable Long id, @RequestBody AppIpWhitelist whitelist) {
        appService.addIpWhitelist(id, whitelist);
        return Result.success();
    }

    /**
     * 移除指定 IP 白名单
     *
     * @param whitelistId 白名单记录 ID
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "删除IP白名单")
    @RequirePerm(value = "app:ipwhitelist:delete", risk = true)
    @DeleteMapping("/ip-whitelist/{whitelistId}")
    public Result<Void> removeIpWhitelist(@PathVariable Long whitelistId) {
        appService.removeIpWhitelist(whitelistId);
        return Result.success();
    }

    /**
     * 更新一条 IP 白名单（T15-4：补编辑与启用/停用）
     *
     * <p>此前只有"新增 / 删除"，临时放行某段 IP 只能删了再加（丢备注、易写错）。
     * 补上编辑后 {@code status}（启用/停用）才真正可用 ——
     * 网关侧 {@code IpWhitelistHandler} 已按 {@code status=1} 过滤。</p>
     *
     * @param whitelistId 白名单记录 ID
     * @param whitelist   新值（IP/CIDR、备注、环境、状态）
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "更新IP白名单")
    @RequirePerm(value = "app:ipwhitelist:update", risk = true)
    @PutMapping("/ip-whitelist/{whitelistId}")
    public Result<Void> updateIpWhitelist(@PathVariable Long whitelistId,
                                          @RequestBody AppIpWhitelist whitelist) {
        appService.updateIpWhitelist(whitelistId, whitelist);
        return Result.success();
    }

    /**
     * 查询应用的限流配置
     *
     * @param id 应用 ID
     * @return 该应用的限流配置（如 QPS 阈值等）
     */
    @Operation(summary = "查询频率限制配置")
    @GetMapping("/{id}/rate-limit")
    public Result<AppRateLimit> getRateLimit(@PathVariable Long id) {
        return Result.success(appService.getRateLimit(id));
    }

    /**
     * 更新应用的限流配置
     *
     * @param id        应用 ID
     * @param rateLimit 限流配置实体
     * @return 操作结果（无业务数据返回）
     */
    @Operation(summary = "更新频率限制")
    @RequirePerm(value = "app:quota:update", risk = true)
    @PutMapping("/{id}/rate-limit")
    public Result<Void> updateRateLimit(@PathVariable Long id, @RequestBody AppRateLimit rateLimit) {
        appService.updateRateLimit(id, rateLimit);
        return Result.success();
    }
}
