package com.gatekeeper.alarm;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.NotifyChannel;
import com.gatekeeper.security.RequirePerm;
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

import java.util.List;

/**
 * 通知渠道管理 Controller — T04-C 告警域
 *
 * <p>接口路径：
 * <ul>
 *   <li>GET    /notify-channel/list                  按 status 筛选渠道列表</li>
 *   <li>GET    /notify-channel/{id}                  渠道详情</li>
 *   <li>POST   /notify-channel/create                创建渠道（{@code notify_channel:create} 高危）</li>
 *   <li>PUT    /notify-channel/{id}/update           修改渠道（{@code sys:notify:update} 高危，T08）</li>
 *   <li>DELETE /notify-channel/{id}                  删除渠道（T07-A 新增，{@code notify_channel:delete} 高危）</li>
 *   <li>POST   /notify-channel/{id}/test             发送测试消息（{@code notify_channel:test} 高危，T07-A）</li>
 * </ul></p>
 *
 * <p>权限策略：变更类端点全部加 {@code @RequirePerm} ——
 * create / update（T08 补 {@code sys:notify:update}）/ delete / test 加；只有 list / detail 不加。</p>
 *
 * @author GateKeeper
 * @since T04-C (APIM V2)
 */
@RestController
@RequestMapping("/notify-channel")
@RequiredArgsConstructor
@Tag(name = "通知渠道", description = "通知渠道管理")
public class NotifyChannelController {

    private final NotifyChannelService notifyChannelService;

    /**
     * 查询通知渠道列表（可选按 status 过滤）。
     */
    @Operation(summary = "查询通知渠道列表")
    @GetMapping("/list")
    public Result<List<NotifyChannel>> list(@RequestParam(required = false) Integer status) {
        return Result.success(notifyChannelService.list(status));
    }
    /**
     * 创建通知渠道（高危）。
     *
     * <p>T04-C 权限点 {@code notify_channel:create}（risk=1）。</p>
     */
    @RequirePerm(value = "notify_channel:create", risk = true)
    @Operation(summary = "创建通知渠道（高危）")
    @PostMapping("/create")
    public Result<NotifyChannel> create(@RequestBody NotifyChannel dto) {
        return Result.success(notifyChannelService.create(dto));
    }

    /**
     * 修改通知渠道（名称/类型/配置/状态）。
     *
     * <p>T08 补权限点 {@code sys:notify:update}（id=341，已播种，授权 SUPER_ADMIN/ADMIN）——
     * 前端 {@code SysNotify.vue} 的「编辑」按钮本就只对该码持有者可见，此前后端零注解
     * 构成「有闸门无注解」的假保护（任何持 token 者可直接 curl 改渠道）。此处仅是两端对齐，
     * **未新增任何权限码**。</p>
     */
    @RequirePerm(value = "sys:notify:update", risk = true)
    @Operation(summary = "修改通知渠道")
    @PutMapping("/{id}/update")
    public Result<Void> update(@PathVariable Long id, @RequestBody NotifyChannel dto) {
        notifyChannelService.update(id, dto);
        return Result.success();
    }

    /**
     * 删除通知渠道（T07-A 新增，高危）。
     *
     * <p>与前端 {@code deleteNotifyChannel(id)} → {@code DELETE /notify-channel/{id}} 严格一致。
     * T07-A 权限点 {@code notify_channel:delete}（risk=1，破坏性操作）。</p>
     */
    @RequirePerm(value = "notify_channel:delete", risk = true)
    @Operation(summary = "删除通知渠道（高危）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        notifyChannelService.delete(id);
        return Result.success();
    }

    /**
     * 发送测试消息（立即向该渠道推送一条测试通知，并记录测试结果）。
     *
     * <p>存在外发副作用，T07-A 补权限点 {@code notify_channel:test}（risk=1），
     * 与 T05 裁定的 {@code api_env_config:test} 属同一类面。</p>
     */
    @RequirePerm(value = "notify_channel:test", risk = true)
    @Operation(summary = "发送测试消息（高危，外发）")
    @PostMapping("/{id}/test")
    public Result<Void> test(@PathVariable Long id) {
        notifyChannelService.test(id);
        return Result.success();
    }
}
