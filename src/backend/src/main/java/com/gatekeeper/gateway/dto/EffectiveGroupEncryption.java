package com.gatekeeper.gateway.dto;

import lombok.Data;

/**
 * 分组级加解密「生效配置」解析结果 — T15-1
 *
 * <p>由 {@code EncryptionConfigResolver#resolveForGroup} 沿分组树向上解析得出，
 * 供**网关加解密**与**前端生效预览**共用（同一份口径）。</p>
 *
 * <p>三种返回值语义（调用方必须区分）：
 * <ul>
 *   <li>{@code null} —— 整条父链都没有非 INHERIT 的配置 ⇒ 调用方继续回退到应用级配置</li>
 *   <li>{@code disabled=true} —— 某层分组显式选了「不需要加解密」⇒ 终止，**不回退**应用级</li>
 *   <li>{@code enabled=true} —— 某层分组启用了加解密 ⇒ 采用本对象的算法/密钥等</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T15-1 (2026-09-15)
 */
@Data
public class EffectiveGroupEncryption {

    /** 来源类型：GROUP=命中某层分组配置 */
    public static final String SOURCE_GROUP = "GROUP";

    /** 命中的三态值（ENABLED / DISABLED） */
    private String mode;

    /** 是否启用加解密（mode=ENABLED） */
    private boolean enabled;

    /** 是否显式关闭加解密（mode=DISABLED） */
    private boolean disabled;

    // ==================== 入参 ====================
    private Boolean requestEncrypted;
    private String requestAlgorithm;
    private String requestMode;
    private String requestKey;
    private String requestIv;
    private String requestPadding;

    // ==================== 返参 ====================
    private Boolean responseEncrypted;
    private String responseAlgorithm;
    private String responseMode;
    private String responseKey;
    private String responseIv;
    private String responsePadding;

    /** 备注（透出给前端展示"为什么这里关了加密"） */
    private String remark;

    // ==================== 来源溯源 ====================
    /** 命中的配置行 ID */
    private Long sourceConfigId;

    /** 命中配置所属的分组 ID（前端据此判断"本分组维护" vs "继承自父级"） */
    private Long sourceGroupId;

    /** 命中配置所属的分组名 */
    private String sourceGroupName;

    /** 继承链路（根 → 起点），如「配网 / 核心指标」，便于前端展示来源 */
    private String sourcePath;

    /** 来源类型常量（当前恒为 GROUP；保留字段以便未来扩展） */
    private String sourceType = SOURCE_GROUP;
}
