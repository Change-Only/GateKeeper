package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 环境表（env）— 运行环境的「主数据」
 *
 * <p>env_code 一旦创建不可修改（架构 D1），是接口配置/授权/配额/凭证/IP 白名单/封禁名单
 * 横向贯穿的环境维度键。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("env")
public class Env {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 环境编码（dev/test/pre/prod），创建后不可修改（架构 D1，原型 envCode） */
    private String envCode;

    /** 环境名称（原型 envName） */
    private String envName;

    /** 该环境网关入口地址（原型 gatewayUrl） */
    private String gatewayUrl;

    /** 排序（原型 sort） */
    private Integer sortOrder;

    /** 状态：1=启用, 0=停用, 2=已废弃（原型 status） */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}