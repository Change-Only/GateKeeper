package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用凭证表（app_credential）— 一应用多密钥 + 环境隔离
 *
 * <p>取代存量 app.app_key/app_secret 的「一应用一密钥」模式。
 * env_code 二级校验防「生产密钥打测试」（架构 D1）。
 * status 复用字典 cred_status：1=启用中, 2=已停用, 3=已吊销, 4=已过期。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("app_credential")
public class AppCredential {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用ID（原型 appId） */
    private Long appId;

    /** AppKey（原型 appKey，全局唯一） */
    private String appKey;

    /** AppSecret（AES 加密存储；原型仅展示 secretMask） */
    private String appSecret;

    /** 密钥掩码展示，如 Yk3m****J5sU（原型 secretMask） */
    private String secretMask;

    /** 密钥别名（原型 alias） */
    private String alias;

    /** 所属环境编码（原型 envCode，默认 prod） */
    private String envCode;

    /** 1=启用中, 2=已停用, 3=已吊销, 4=已过期（原型 status / dict cred_status） */
    private Integer status;

    /** 过期时间，NULL=永不过期（原型 expireTime） */
    private LocalDateTime expireTime;

    /** 最近使用时间（原型 lastUsedTime） */
    private LocalDateTime lastUsedTime;

    /** 最近使用IP（原型 lastUsedIp） */
    private String lastUsedIp;

    /** 1=轮换中的新密钥, 0=常规（原型 rotateFlag） */
    private Integer rotateFlag;

    /** 创建人 */
    private String createdBy;

    /** 创建时间（原型 createTime） */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}