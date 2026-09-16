package com.gatekeeper.controller;

import com.gatekeeper.aspect.ApiChangeLog;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.dto.InterfaceDetailVo;
import com.gatekeeper.dto.InterfaceImportRequest;
import com.gatekeeper.dto.InterfaceImportResult;
import com.gatekeeper.dto.InterfaceListVo;
import com.gatekeeper.dto.InterfaceTestRequest;
import com.gatekeeper.dto.InterfaceTestResult;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.InterfaceImportService;
import com.gatekeeper.service.InterfaceService;
import com.gatekeeper.service.InterfaceTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * 接口管理 Controller
 *
 * <p>T03b 升级：把接口从「转发的路径」升级为「接口资产」。
 * <ul>
 *   <li>GET    /interface/list                列表带分组名/业务线名（跨表冗余）</li>
 *   <li>GET    /interface/{apiId}             详情聚合（基本信息 + 参数 + 版本 + 环境配置 + 最近变更）</li>
 *   <li>POST   /interface                     新增接口（自动写变更历史 CREATE）</li>
 *   <li>POST   /interface/import              导入 OpenAPI 3.x / Swagger 3.0 文档（T18，groupId 必填）</li>
 *   <li>PUT    /interface/{apiId}             编辑接口（自动写变更历史 UPDATE）</li>
 *   <li>PUT    /interface/{apiId}/status/{status} 启用/停用接口</li>
 *   <li>POST   /interface/{apiId}/publish     发布接口（{@code api:publish} 高危）</li>
 *   <li>DELETE /interface/{apiId}             删除接口（{@code api:delete} 高危，需先下线全部版本）</li>
 * </ul></p>
 *
 * <p>变更留痕：所有写操作用 {@code @ApiChangeLog} 注解，由
 * {@link com.gatekeeper.aspect.ApiChangeLogAspect} 在成功返回后自动追加 api_change_log。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@RestController
@RequestMapping("/interface")
@RequiredArgsConstructor
@Tag(name = "接口", description = "接口管理接口")
public class InterfaceController {

    private final InterfaceService interfaceService;
    private final InterfaceTestService interfaceTestService;
    /** T18：OpenAPI 3.x 文档导入 */
    private final InterfaceImportService interfaceImportService;

    /**
     * 分页查询接口列表（T03b 增强：行内含分组名 groupName）。
     *
     * @param current       当前页码（默认第 1 页）
     * @param size          每页条数（默认 10 条）
     * @param interfaceName 接口名称（可选，模糊匹配）
     * @param groupId       所属分组 ID（可选；传父分组时返回其全部子孙分组的接口）
     * @return 分页结果
     */
    @Operation(summary = "分页查询接口列表")
    @GetMapping("/list")
    public Result<PageResult<InterfaceListVo>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String interfaceName,
            @RequestParam(required = false) Long groupId) {
        return Result.success(interfaceService.pageQueryEnriched(current, size, interfaceName, groupId));
    }

    /**
     * 接口详情聚合（T03b 新增）。
     *
     * @param apiId 接口 ID
     * @return 聚合详情（基本信息 + 4 类参数 + 版本列表 + 环境配置 + 最近 5 条变更）
     */
    @Operation(summary = "接口详情聚合")
    @GetMapping("/{apiId}")
    public Result<InterfaceDetailVo> detail(@PathVariable Long apiId) {
        return Result.success(interfaceService.getDetail(apiId));
    }

    /**
     * 新增接口（自动写变更历史 CREATE）。
     *
     * @param apiInterface 接口实体（含名称、路径、所属分组等）
     * @return 创建成功后的接口实体
     */
    @ApiChangeLog(value = "新增接口", changeType = "CREATE", fieldName = "interface", fieldLabel = "接口")
    @Operation(summary = "新增接口")
    @RequirePerm(value = "api:create", risk = true)
    @PostMapping
    public Result<ApiInterface> create(@RequestBody ApiInterface apiInterface) {
        return Result.success(interfaceService.createInterface(apiInterface));
    }

    /**
     * 导入 OpenAPI 3.x / Swagger 3.0 文档（T18）。
     *
     * <p><b>权限点复用 {@code api:create}</b>：导入的产物就是新建的接口资产，
     * 与「逐条新建接口」是同一种能力，只是入口批量化了。刻意不新开 {@code api:import} ——
     * 那会让"能建的人不能导、能导的人不能建"这种同能力双码的怪状态出现，
     * 也要多维护一条权限播种记录。</p>
     *
     * <p><b>为什么不加 {@code @ApiChangeLog}</b>：该注解的 apiId 解析依赖
     * 「入参是接口实体 / 返回体是 ApiInterface」，而导入一次会创建 N 条接口、
     * 返回的是统计结果，无法归属到某一个 apiId。硬塞进去只会写出 N 条
     * 指向同一个 apiId 的错误变更记录。导入动作本身由操作日志（OperationLog）留痕。</p>
     *
     * <p>🔴 <b>groupId 必填</b>（需求硬约束「导入时必须先选择分组」）由服务层校验并返回 400；
     * 前端禁用提交按钮只是体验层闸门，脚本可绕，故后端必须独立拦一道。</p>
     *
     * @param req         导入请求（groupId 必填 + 文档正文 + 可选文件名）
     * @param httpRequest 用于取 {@code X-USER-ID} / {@code X-USERNAME}（由 {@code JwtAuthInterceptor} 写入），
     *                    作为导入接口的 owner
     * @return 导入结果（计数 + 逐条明细 + 后续待办提示）
     */
    @Operation(summary = "导入 OpenAPI 3.0 文档")
    @RequirePerm(value = "api:create", risk = true)
    @PostMapping("/import")
    public Result<InterfaceImportResult> importSpec(@RequestBody InterfaceImportRequest req,
                                                     HttpServletRequest httpRequest) {
        return Result.success(interfaceImportService.importSpec(
                req, currentUserId(httpRequest), currentUserName(httpRequest)));
    }

    /**
     * 从请求属性解析当前用户 id。
     *
     * <p>与 {@code OperationLogAspect} / {@code ApiChangeLogAspect} 同一口径：
     * {@code JwtAuthInterceptor} 把 JWT 的 {@code uid} claim 写进请求属性（Long 类型）。
     * 解析不出来时返回 null —— 导入仍可继续，只是接口没有 owner；
     * 这比因为一个附加字段而让整个导入 500 合理。</p>
     */
    private Long currentUserId(HttpServletRequest request) {
        Object v = request.getAttribute("X-USER-ID");
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v != null) {
            try {
                return Long.valueOf(v.toString());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    /** 从请求属性解析当前登录名（JWT subject），作为导入接口的 owner_name。 */
    private String currentUserName(HttpServletRequest request) {
        Object v = request.getAttribute("X-USERNAME");
        return v == null ? null : v.toString();
    }

    /**
     * 编辑接口信息（自动写变更历史 UPDATE）。
     *
     * @param apiId        接口 ID
     * @param apiInterface 待更新的接口信息
     * @return 操作结果
     */
    @ApiChangeLog(value = "编辑接口", changeType = "UPDATE", fieldName = "interface", fieldLabel = "接口")
    @Operation(summary = "更新接口")
    @RequirePerm(value = "api:update", risk = true)
    @PutMapping("/{apiId}")
    public Result<Void> update(@PathVariable Long apiId, @RequestBody ApiInterface apiInterface) {
        interfaceService.updateInterface(apiId, apiInterface);
        return Result.success();
    }

    /**
     * 启用/停用接口。
     *
     * @param apiId  接口 ID
     * @param status 目标状态（如 1 启用、0 停用）
     * @return 操作结果
     */
    @Operation(summary = "更新接口状态")
    @RequirePerm(value = "api:disable", risk = true)
    @PutMapping("/{apiId}/status/{status}")
    public Result<Void> updateStatus(@PathVariable Long apiId, @PathVariable Integer status) {
        interfaceService.updateStatus(apiId, status);
        return Result.success();
    }

    /**
     * 删除接口（高危，需先下线全部版本）。
     *
     * <p>T02 权限点：{@code api:delete}。T03b 增加「版本全下线」前置校验。</p>
     *
     * @param apiId 接口 ID
     * @return 操作结果
     */
    @ApiChangeLog(value = "删除接口", changeType = "DELETE", fieldName = "interface", fieldLabel = "接口")
    @RequirePerm(value = "api:delete", risk = true)
    @Operation(summary = "删除接口")
    @DeleteMapping("/{apiId}")
    public Result<Void> delete(@PathVariable Long apiId) {
        interfaceService.deleteInterface(apiId);
        return Result.success();
    }

    /**
     * 发布接口（高危）。
     *
     * <p>T02 新增接口，覆盖原型 {@code api:publish} 权限点。
     * 版本级发布请使用 {@code POST /api-version/{id}/publish}（T03b）。</p>
     *
     * @param apiId 接口 ID
     * @return 操作结果
     */
    @RequirePerm(value = "api:publish", risk = true)
    @Operation(summary = "发布接口")
    @PostMapping("/{apiId}/publish")
    public Result<Void> publish(@PathVariable Long apiId) {
        interfaceService.updateStatus(apiId, 1);
        return Result.success();
    }

    /**
     * 接口试调测试（T13）。
     *
     * <p>两种模式：{@code DIRECT} 直连上游（验证配置与上游连通性，无需应用凭证）、
     * {@code GATEWAY} 走网关（用真实应用凭证签名，把鉴权/限流/权限/Mock/日志整条链路跑一遍）。</p>
     *
     * <p>权限点 {@code interface:test}（risk=false）：试调不写业务数据，但会真实外呼到上游，
     * 属"可对外产生副作用"的动作，故仍需显式授权，不放进只读豁免。</p>
     *
     * @param apiId       接口 ID
     * @param req         试调参数（可为空 ⇒ 直连 + 默认环境）
     * @param httpRequest 当前请求（走网关模式据此推导自身地址与 context-path）
     * @return 试调结果（含状态码/耗时/报文/生效配置来源/过程说明）
     */
    @RequirePerm(value = "interface:test", risk = false)
    @Operation(summary = "接口试调测试")
    @PostMapping("/{apiId}/test")
    public Result<InterfaceTestResult> test(@PathVariable Long apiId,
                                            @RequestBody(required = false) InterfaceTestRequest req,
                                            HttpServletRequest httpRequest) {
        return Result.success(interfaceTestService.test(apiId, req, httpRequest));
    }
}
