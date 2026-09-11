package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 凭证轮换请求 DTO — 触发「创建新凭证 + 标记旧凭证 7 天后吊销」的灰度轮换流程
 *
 * <p>T03a 零停机轮换的核心请求：
 * <ol>
 *   <li>校验 appId + envCode 下不存在 rotateFlag=1 的轮换中凭证（否则拒绝）</li>
 *   <li>原 rotateFlag=0 的主密钥 alias 改为「{原 alias}-旧(将于 N 天后吊销)」并写 expireTime=now+7d</li>
 *   <li>新建凭证 alias「{原 alias}-轮换中(新)」 rotateFlag=1 status=1</li>
 *   <li>仅本接口响应一次性返回新凭证 appKey + appSecret 明文</li>
 * </ol></p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@Data
public class CredentialRotateRequest {

    /** 应用ID */
    @NotNull(message = "应用ID不能为空")
    private Long appId;

    /** 环境编码 */
    @NotBlank(message = "环境编码不能为空")
    private String envCode;

    /** 可选：自定义新密钥别名（默认追加「-轮换中(新)」后缀） */
    private String newAlias;

    /**
     * 可选：旧凭证到期天数（默认 7）。
     *
     * <p>取值范围 [1, 30]，超过范围时由 Service 兜底为 7。</p>
     */
    private Integer expireAfterDays;

    /** 可选：操作人上下文（如系统/API），写库时落入 created_by */
    private String operatorName;
}
