package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台级加解密「总开关」（sys_encryption_config）— T16-1
 *
 * <p><b>需求口径</b>（用户 2026-09-15 拍板）：**仅总闸，不留平台密钥**。
 * <ul>
 *   <li>{@code enabled = 1} ⇒ 网关按既有优先级正常解析：
 *       接口级 &gt; 分组级（沿分组树向上继承）&gt; 应用级 &gt; 明文；</li>
 *   <li>{@code enabled = 0} ⇒ <b>全局强制明文</b>：网关跳过整条解析链，
 *       忽略接口级 / 分组级 / 应用级的所有配置。</li>
 * </ul>
 * 因此本表**没有** algorithm / key / iv / mode / padding 列 —— 平台级不提供兜底密钥。</p>
 *
 * <p><b>单行表</b>：{@code id} 恒为 {@link #SINGLETON_ID}，用 {@code IdType.INPUT} 而非自增。</p>
 *
 * <p><b>缺行等于启用</b>：SQL 脚本刻意不播种任何行，
 * 于是「存量库 / 新建库 / 忘了跑脚本」三种情况行为完全一致 —— 零迁移风险。</p>
 *
 * @author GateKeeper
 * @since T16-1 (2026-09-15)
 */
@Data
@TableName("sys_encryption_config")
public class SysEncryptionConfig {

    /** 单行配置的固定主键 */
    public static final long SINGLETON_ID = 1L;

    /** 总开关：启用（按各层级配置解析） */
    public static final int ENABLED_ON = 1;

    /** 总开关：关闭（全局强制明文） */
    public static final int ENABLED_OFF = 0;

    /** 固定主键 id=1（不做自增） */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 平台加解密总开关：1=启用；0=全局强制明文 */
    private Integer enabled;

    /** 备注：为什么开/关（允许被清空，故写入侧用显式 set，不走 NOT_NULL 策略） */
    private String remark;

    /** 最后修改人（sys_user.id，取自请求属性 X-USER-ID） */
    private Long updatedBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
