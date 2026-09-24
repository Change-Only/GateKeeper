package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 系统用户表（sys_user）— 管理后台登录账号
 * 存储登录凭证与个人信息，密码采用 BCrypt 加密存储
 */
@TableName("sys_user")
public class SysUser {
    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号（唯一） */
    private String username;

    /**
     * 登录密码（BCrypt 加密）。
     *
     * <p>P1-7：加 {@link JsonProperty.Access#WRITE_ONLY} —— <b>可反序列化输入、绝不序列化输出</b>。
     * 这样 {@code GET /system/user/list} 等任何出参路径都不会再泄漏 BCrypt 散列（配合
     * {@code spring.jackson.default-property-inclusion: always} 尤其必要）。</p>
     *
     * <p>⚠️ <b>不要改用 {@code @JsonIgnore}</b>：那会<b>同时禁掉反序列化</b>，而登录/建号/改密都需要
     * 把 {@code password} 从请求体读进来，会导致这些功能直接坏掉。</p>
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 真实姓名 */
    private String realName;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 账号状态（1=启用 0=停用） */
    private Integer status;

    /** 最后登录时间 */
    private LocalDateTime lastLoginAt;

    /** 最后登录IP */
    private String lastLoginIp;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
