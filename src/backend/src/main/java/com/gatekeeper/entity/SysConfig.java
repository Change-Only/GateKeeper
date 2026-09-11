package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统参数配置表（sys_config）
 *
 * <p>config_group: SECURITY/GATEWAY/LOG/DEFAULT。
 * sensitive=1 的配置接口返回时脱敏，不在前端明文展示（架构 D7）。
 *
 * <p>注：列名 {@code sensitive} 在 MySQL 8 中是保留字，字段映射使用反引号包裹的列名。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("sys_config")
public class SysConfig {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键（原型 configKey，全局唯一） */
    private String configKey;

    /** 配置值（原型 configValue） */
    private String configValue;

    /** 配置分组：SECURITY/GATEWAY/LOG/DEFAULT（原型 configGroup） */
    private String configGroup;

    /** 配置名称（原型 configName） */
    private String configName;

    /** 1=敏感配置(响应脱敏), 0=普通（原型 sensitive，MySQL 8 保留字需反引号） */
    @TableField("`sensitive`")
    private Integer sensitive;

    /** 1=内置不可删除, 0=可删除（原型 builtIn） */
    private Integer builtIn;

    /** 备注（原型 remark） */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}