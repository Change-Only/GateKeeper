package com.gatekeeper.gateway.dto;

import lombok.Data;

/**
 * 生效环境配置（解析结果） — T13
 *
 * <p>把「接口在某环境下到底用哪个上游、超时多少、要不要 Mock」这一件事的答案收敛成一个对象，
 * 由 {@code com.gatekeeper.gateway.EnvConfigResolver} 产出。网关侧（ForwardHandler）与
 * 前端只读预览共用同一个解析逻辑，避免"页面显示的"和"网关实际用的"两套口径。</p>
 *
 * <p>解析优先级（高 → 低）：
 * <ol>
 *   <li>{@link #SOURCE_INTERFACE} 接口级覆盖（老表 api_env_config，保留兼容，UI 已不再提供入口）</li>
 *   <li>{@link #SOURCE_GROUP} 分组继承（新表 api_group_env_config，沿分组树向上找最近一层）</li>
 *   <li>{@link #SOURCE_DEFAULT} 无任何配置 ⇒ 回退 {@code api_interface.backend_url} 与接口自身超时</li>
 * </ol></p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@Data
public class EffectiveEnvConfig {

    /** 来源：接口级覆盖 */
    public static final String SOURCE_INTERFACE = "INTERFACE";
    /** 来源：分组继承 */
    public static final String SOURCE_GROUP = "GROUP";
    /** 来源：无配置，回退到接口默认后端地址 */
    public static final String SOURCE_DEFAULT = "DEFAULT";

    /** 环境编码 */
    private String envCode;

    /** 服务前缀（不含 URI）；DEFAULT 来源时为 null，表示应回退到 api_interface.backend_url */
    private String upstreamUrl;

    /** 连接超时(ms)；null 表示回退接口自身 timeoutMs */
    private Integer connectTimeout;

    /** 读取超时(ms)；null 表示回退接口自身 timeoutMs */
    private Integer readTimeout;

    /** 重试次数；null 视为 0 */
    private Integer retryCount;

    /** 1=开启Mock（网关短路不转发），0/null=关闭 */
    private Integer mockEnabled;

    /** Mock 返回的 HTTP 状态码（默认 200） */
    private Integer mockStatus;

    /** Mock 返回体（留空 = 返回默认提示 JSON） */
    private String mockResponse;

    /** 来源类型：INTERFACE / GROUP / DEFAULT */
    private String sourceType;

    /** 来源配置行的 ID（DEFAULT 时为 null） */
    private Long sourceConfigId;

    /** 来源分组 ID（仅 SOURCE_GROUP 有值） */
    private Long sourceGroupId;

    /** 来源分组名称（仅 SOURCE_GROUP 有值） */
    private String sourceGroupName;

    /** 来源链路描述，如「配网 / 核心指标」（仅 SOURCE_GROUP 有值），供界面直接展示 */
    private String sourcePath;

    /** 配置状态：1=已配置, 2=已验证 */
    private Integer configStatus;

    /** 是否命中 Mock（mockEnabled == 1） */
    public boolean isMockOn() {
        return mockEnabled != null && mockEnabled == 1;
    }

    /** 构造一个 DEFAULT 结果（什么都没有时的兜底） */
    public static EffectiveEnvConfig fallback(String envCode) {
        EffectiveEnvConfig e = new EffectiveEnvConfig();
        e.setEnvCode(envCode);
        e.setSourceType(SOURCE_DEFAULT);
        return e;
    }
}
