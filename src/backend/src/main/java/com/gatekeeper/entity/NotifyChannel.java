package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知渠道表（notify_channel）
 *
 * <p>channel_type: WECOM/DINGTALK/EMAIL/SMS/WEBHOOK。
 * channel_config 以 JSON 字符串存储渠道配置（webhook 地址 / SMTP 等）。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("notify_channel")
public class NotifyChannel {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 渠道名称（原型 channelName） */
    private String channelName;

    /** 渠道类型：WECOM/DINGTALK/EMAIL/SMS/WEBHOOK（原型 channelType） */
    private String channelType;

    /** 渠道配置 JSON（webhook 地址/SMTP 等） */
    private String channelConfig;

    /** 1=启用, 0=停用（原型 status） */
    private Integer status;

    /** 最近测试时间（原型 lastTestTime） */
    private LocalDateTime lastTestTime;

    /** 最近测试结果（原型 lastTestResult） */
    private String lastTestResult;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}