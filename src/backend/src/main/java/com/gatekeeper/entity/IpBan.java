package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * IP封禁表（ip_ban）— 恶意来源IP的封禁记录
 * 支持全局封禁（app_id=NULL）与应用级封禁，封禁状态同步写入 Redis 供网关 O(1) 查询
 */
@TableName("ip_ban")
public class IpBan {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被封禁的IP地址 */
    private String ipAddress;

    /** 关联应用ID（NULL=全局封禁） */
    private Long appId;

    /** 封禁原因 */
    private String banReason;

    /** 封禁开始时间 */
    private LocalDateTime banStartTime;

    /** 封禁结束时间 */
    private LocalDateTime banEndTime;

    /** 封禁状态（1=封禁中 0=已解封） */
    private Integer banStatus;

    /** 封禁类型（MANUAL=手动 AUTO=自动） */
    private String banType;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
