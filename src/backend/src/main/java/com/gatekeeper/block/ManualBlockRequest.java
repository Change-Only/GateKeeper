package com.gatekeeper.block;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 人工封禁请求 — manual-block 接口入参
 *
 * @author GateKeeper
 * @since T04-D (APIM V2)
 */
@Data
public class ManualBlockRequest {

    /** 封禁目标：IP 或 AppKey/AppId（按规则 scope 决定语义） */
    @NotBlank(message = "封禁目标(target)不能为空")
    private String target;

    /** 封禁时长(秒)，可选；不传则使用规则默认 ttlSeconds */
    private Integer ttlSeconds;

    /** 封禁原因，可选（不传则回退到规则描述） */
    private String reason;
}
