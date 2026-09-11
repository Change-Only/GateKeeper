package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 应用凭证 DTO — 应用凭证管理页面入参/出参对象
 *
 * <p>T03a 多套凭证 + 灰度轮换：取代 app.app_key/app_secret 单密钥模型。一应用在不同
 * 环境（dev/test/pre/prod）下可有独立凭证；同环境轮换时存在主密钥+轮换中密钥共存窗口。</p>
 *
 * <p>字段命名严格对齐原型枚举字典 §一（credentials.status）：
 * <ul>
 *   <li>status: 0=未分配, 1=启用中, 2=已停用, 3=已吊销, 4=已过期</li>
 *   <li>rotateFlag: 0=主密钥, 1=轮换中新密钥</li>
 * </ul></p>
 *
 * <p>安全约束：
 * <ul>
 *   <li>{@code appSecret} 仅在 create / rotate 的「明文返回窗口」中返回一次</li>
 *   <li>{@code secretMask} 是展示字段（首 4 + **** + 末 4），落库与列表必带</li>
 *   <li>列表/详情接口严禁返回 {@code appSecret} 明文</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@Data
public class AppCredentialDto {

    /** 主键ID（创建时为空，更新/吊销必填） */
    private Long id;

    /** 应用ID */
    @NotNull(message = "应用ID不能为空")
    private Long appId;

    /** 所属环境编码 */
    @NotBlank(message = "环境编码不能为空")
    private String envCode;

    /** AppKey（服务端生成，格式 ak_{envCode}_{16位随机}），创建/详情时返回 */
    private String appKey;

    /** AppSecret 明文：仅 create / rotate 响应时返回一次，绝不持久化展示 */
    private String appSecret;

    /** 密钥掩码展示，如 Yk3m****J5sU，所有非 create/rotate 响应必带 */
    private String secretMask;

    /** 用户自定义别名，如「生产-主密钥」 */
    @Size(max = 128, message = "别名长度不能超过128")
    private String alias;

    /** 状态：0=未分配, 1=启用中, 2=已停用, 3=已吊销, 4=已过期 */
    private Integer status;

    /** 过期时间，null=永不过期 */
    private LocalDateTime expireTime;

    /** 最近使用时间（网关回写） */
    private LocalDateTime lastUsedTime;

    /** 最近使用IP（网关回写） */
    private String lastUsedIp;

    /** 轮换标志：0=主密钥, 1=轮换中新密钥 */
    private Integer rotateFlag;

    /** 创建人 */
    private String createdBy;

    /** 创建时间（响应字段） */
    private LocalDateTime createTime;

    /** 更新时间（响应字段） */
    private LocalDateTime updatedAt;
}
