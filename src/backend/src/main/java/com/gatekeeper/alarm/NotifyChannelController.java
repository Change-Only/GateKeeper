package com.gatekeeper.alarm;

import com.gatekeeper.common.Result;
import com.gatekeeper.entity.NotifyChannel;
import com.gatekeeper.security.RequirePerm;
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

import java.util.List;

/**
 * 通知渠道管理 Controller — T04-C 告警域
 *
 * <p>接口路径：
 * <ul>
 *   <li>GET    /notify-channel/list                  按 status 筛选渠道列表</li>
 *   <li>GET    /notify-channel/{id}                  渠道详情</li>
 *   <li>POST   /notify-channel/create                创建渠道（{@code notify_channel:create} 高危）</li>
 *   <li>PUT    /notify-channel/{id}/update           修改渠道</li>
 *   <li>POST   /notify-channel/{id}/test             发送测试消息</li>
 * </ul></p>
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
     * 通知渠道详情。
     */
    @Operation(summary = "通知渠道详情")
    @GetMapping("/{id}")
    public Result<NotifyChannel> detail(@PathVariable Long id) {
        return Result.success(notifyChannelService.get(id));
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
     */
    @Operation(summary = "修改通知渠道")
    @PutMapping("/{id}/update")
    public Result<Void> update(@PathVariable Long id, @RequestBody NotifyChannel dto) {
        notifyChannelService.update(id, dto);
        return Result.success();
    }

    /**
     * 发送测试消息（立即向该渠道推送一条测试通知，并记录测试结果）。
     */
    @Operation(summary = "发送测试消息")
    @PostMapping("/{id}/test")
    public Result<Void> test(@PathVariable Long id) {
        notifyChannelService.test(id);
        return Result.success();
    }
}
