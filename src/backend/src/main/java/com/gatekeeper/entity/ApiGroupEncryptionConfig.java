package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口分组加解密配置表（api_group_encryption_config） — T15-1
 *
 * <p>需求（2026-09-15 用户第 1 条）：加解密配置下沉到「接口分组侧」，
 * 下级（子分组 / 组内接口）**沿分组树向上继承**，并且**可以选择不需要加解密**。</p>
 *
 * <p><b>三态 mode 是本表存在的理由</b>：老表 {@link ApiEncryptionConfig} 用
 * {@code request_encrypted}（boolean）表达配置，只有「是 / 否」两态。
 * 一旦把它挂到分组上，子分组"落库"这个动作本身就等于切断了继承 ——
 * 用户无法表达「我什么都不配，请继续继承父级」。
 * 因此引入显式三态：
 * <ul>
 *   <li>{@link #MODE_INHERIT}（默认）：本行等价于"未配置"，沿 parent_id 继续上溯</li>
 *   <li>{@link #MODE_ENABLED}：本分组启用加解密，终止上溯并采用本行配置</li>
 *   <li>{@link #MODE_DISABLED}：显式不需要加解密，终止上溯且**不回退**应用级配置</li>
 * </ul></p>
 *
 * <p>与老表的关系（演进式重构：只加表，两张老表一行不动）：
 * <ul>
 *   <li>{@link ApiEncryptionConfig}（接口级）保持最高优先级，且保留"存在即终止"的老语义；</li>
 *   <li>{@link AppEncryptionConfig}（应用级）降为最低优先级。</li>
 * </ul>
 * 完整优先级：<b>接口级 &gt; 分组级（本表，沿父链） &gt; 应用级</b>。
 * 解析由 {@code com.gatekeeper.gateway.EncryptionConfigResolver} 统一负责，
 * 前端"生效预览"与网关实际行为共用同一份逻辑，杜绝两套口径。</p>
 *
 * <p>唯一键 {@code group_id}：一个分组一条配置。加解密是接口契约的一部分，**不分环境**
 * （故刻意不像 {@code api_group_env_config} 那样带 env_code）。</p>
 *
 * @author GateKeeper
 * @since T15-1 (2026-09-15)
 */
@Data
@TableName("api_group_encryption_config")
public class ApiGroupEncryptionConfig {

    /** 继承上级（默认；等价于"本分组未配置"，继续沿父链上溯） */
    public static final String MODE_INHERIT = "INHERIT";

    /** 本分组启用加解密（终止上溯，采用本行配置） */
    public static final String MODE_ENABLED = "ENABLED";

    /** 显式不需要加解密（终止上溯，且不回退应用级） */
    public static final String MODE_DISABLED = "DISABLED";

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口分组ID（关联 api_group.id；唯一） */
    private Long groupId;

    /** 三态：INHERIT / ENABLED / DISABLED */
    private String mode;

    // ==================== 入参加密 ====================

    /** 入参是否加密（仅 mode=ENABLED 时有意义） */
    private Boolean requestEncrypted;

    /** 入参加密算法（SM4 / AES） */
    private String requestAlgorithm;

    /** 入参加密模式（ECB/CBC/CFB/OFB/CTR） */
    private String requestMode;

    /** 入参密钥（Base64） */
    private String requestKey;

    /** 入参 IV 向量（Base64；ECB 模式可空） */
    private String requestIv;

    /** 入参填充方式 */
    private String requestPadding;

    // ==================== 返参加密 ====================

    /** 返参是否加密（仅 mode=ENABLED 时有意义） */
    private Boolean responseEncrypted;

    /** 返参加密算法 */
    private String responseAlgorithm;

    /** 返参加密模式 */
    private String responseMode;

    /** 返参密钥（Base64） */
    private String responseKey;

    /** 返参 IV 向量（Base64） */
    private String responseIv;

    /** 返参填充方式 */
    private String responsePadding;

    /** 备注（说明为何启用 / 为何显式关闭） */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
