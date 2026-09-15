package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口信息可见性白名单（sys_interface_visibility）— T17
 *
 * <p><b>它授权的不是「能不能进网关」，而是「控制台里能不能看到接口明文」</b>。
 * 这两件事很容易被混淆，务必分清：</p>
 * <ul>
 *   <li>{@code sys_ip_whitelist} / {@code app_ip_whitelist} —— <b>网络层准入</b>：
 *       控制「谁可以调用接口」，在网关责任链里校验来源 IP；</li>
 *   <li>{@link SysInterfaceVisibility}（本类）—— <b>数据可见性</b>：
 *       控制「谁能从管理后台看到接口路径与参数明文」，
 *       与来源 IP、与应用凭证、与网关路由都无关。</li>
 * </ul>
 *
 * <p><b>生效规则（与 {@code InterfaceVisibilityServiceImpl} 必须一致）</b>：
 * <ol>
 *   <li>加密开关关闭 ⇒ 不看白名单，<b>全部可见</b>（也不掩码）；</li>
 *   <li>加密开关启用 ⇒ 命中任一即返回明文：
 *       SUPER_ADMIN 兜底 / 是该接口 owner / 命中 (USER,uid) / 命中 (ROLE,任一角色)；</li>
 *   <li><b>表为空</b> ⇒ 只有 SUPER_ADMIN 与 owner 可见，其余人掩码。</li>
 * </ol>
 *
 * <p>⚠ 注意这条与 {@code sys_ip_whitelist} 的「空表 = 不限制」<b>语义正好相反</b>：
 * 那条是「准入」闸门，空表不限制才安全（怕误拦）；本表是「保护」闸门，
 * 空表也按"未授权"处理才安全（怕误放导致明文泄漏）。
 * fail-safe 方向按语义定，不要照抄另一个（见 docs/CONTRACTS §18）。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
@Data
@TableName("sys_interface_visibility")
public class SysInterfaceVisibility {

    /** 主体类型：具体用户（subject_id = sys_user.id） */
    public static final String TYPE_USER = "USER";

    /** 主体类型：角色（subject_id = sys_role.id） */
    public static final String TYPE_ROLE = "ROLE";

    /** 状态：启用（参与判定） */
    public static final int STATUS_ENABLED = 1;

    /** 状态：停用（不参与判定，便于临时摘除而不丢记录） */
    public static final int STATUS_DISABLED = 0;

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 主体类型：USER=具体用户，ROLE=角色 */
    private String subjectType;

    /** 主体ID：USER → sys_user.id；ROLE → sys_role.id */
    private Long subjectId;

    /** 备注（说明为何允许其查看接口明文） */
    private String remark;

    /** 状态：1=启用，0=停用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
