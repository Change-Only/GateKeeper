package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口信息加密开关（sys_interface_crypto_config）— T17
 *
 * <p><b>管的是什么</b>：GateKeeper <b>自身数据</b>的字段级存储加密 ——
 * 即「别人登录控制台看不到我的接口信息」里的那个「加密」。<b>与网关对外报文加解密毫无关系</b>
 * （后者是 T15/T16 的三档配置 + 已撤销的 T16-1 总闸，勿混）。</p>
 *
 * <p><b>语义</b>：
 * <ul>
 *   <li>{@code enabled = 1} ⇒ 新写入的 {@code api_interface.interface_path} 与
 *       {@code api_param.field_name/example/description} 以 AES-256-CBC 密文落库；
 *       控制台按可见性白名单返回明文或掩码；</li>
 *   <li>{@code enabled = 0} ⇒ 新写入一律明文，且控制台<b>不再掩码</b>（全量可见）。
 *       读取侧仍会解密历史密文行，所以「关开关」是可逆的。</li>
 * </ul></p>
 *
 * <p><b>单行表 + 缺行=启用</b>：{@code id} 恒为 {@link #SINGLETON_ID}，用
 * {@code IdType.INPUT}；SQL 脚本刻意不播种，于是存量库/新建库/未跑脚本三种情况行为一致。</p>
 *
 * @author GateKeeper
 * @since T17 (2026-09-14)
 */
@Data
@TableName("sys_interface_crypto_config")
public class SysInterfaceCryptoConfig {

    /** 单行配置的固定主键 */
    public static final long SINGLETON_ID = 1L;

    /** 启用（密文落库 + 按白名单解密） */
    public static final int ENABLED_ON = 1;

    /** 关闭（明文落库 + 全量可见） */
    public static final int ENABLED_OFF = 0;

    /** 固定主键 id=1（不做自增） */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 接口信息加密开关：1=启用；0=关闭 */
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
