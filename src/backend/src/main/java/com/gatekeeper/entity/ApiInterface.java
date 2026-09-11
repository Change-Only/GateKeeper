package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 接口表（api_interface）— 网关代理的目标接口定义
 * 定义网关对外暴露路径与后端真实服务地址的映射，是网关转发与权限控制的核心实体
 */
@TableName("api_interface")
public class ApiInterface {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口名称 */
    private String interfaceName;

    /** 网关对外暴露路径（/gateway/ 开头） */
    private String interfacePath;

    /** 请求方法（GET/POST/PUT/DELETE） */
    private String requestMethod;

    /** 入参类型（JSON/FORM/QUERY） */
    private String requestParamType;

    /** 所属分组ID（关联 api_group.id） */
    private Long groupId;

    /** 后端真实服务地址（网关转发目标） */
    private String backendUrl;

    /** 接口状态（1=启用 0=停用） */
    private Integer status;

    /** 转发超时时间（毫秒） */
    private Integer timeoutMs;

    /** 接口描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
