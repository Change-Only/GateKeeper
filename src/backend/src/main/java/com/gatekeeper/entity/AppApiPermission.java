package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 应用接口权限表（app_api_permission）— 应用对接口的访问授权关系
 * 记录某应用被授权访问哪些接口，网关据此判断请求是否越权
 */
@TableName("app_api_permission")
public class AppApiPermission {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用ID（关联 app.id） */
    private Long appId;

    /** 接口ID（关联 api_interface.id） */
    private Long interfaceId;

    /** 授权状态（1=已授权 0=已取消） */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
