package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 应用IP白名单表（app_ip_whitelist）— 应用的来源IP访问控制
 * 存储应用允许访问的IP或CIDR网段，来源IP不在白名单内的请求将被网关拦截
 */
@TableName("app_ip_whitelist")
public class AppIpWhitelist {
    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用ID（关联 app.id） */
    private Long appId;

    /** 允许访问的IP或CIDR网段（如 192.168.1.0/24） */
    private String ipCidr;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
