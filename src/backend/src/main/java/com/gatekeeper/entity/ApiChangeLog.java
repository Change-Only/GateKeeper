package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口变更历史表（api_change_log）
 *
 * <p>change_type: CREATE/UPDATE/PUBLISH/OFFLINE/DELETE。
 * 旧值/新值以字符串形式存储（避免结构变更破坏历史）。
 *
 * <p>注：字段 {@code create_time} 对应数据库列 create_time（非 created_at），以保持与原型一致性。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("api_change_log")
public class ApiChangeLog {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口ID（原型 apiId） */
    private Long apiId;

    /** 变更类型：CREATE/UPDATE/PUBLISH/OFFLINE/DELETE（原型 changeType） */
    private String changeType;

    /** 变更字段名（原型 fieldName） */
    private String fieldName;

    /** 变更字段中文名（原型 fieldLabel） */
    private String fieldLabel;

    /** 变更前值（原型 oldValue） */
    private String oldValue;

    /** 变更后值（原型 newValue） */
    private String newValue;

    /** 操作人ID */
    private Long operatorId;

    /** 操作人姓名（原型 operatorName） */
    private String operatorName;

    /** 变更原因（原型 changeReason） */
    private String changeReason;

    /** 变更时间（原型 createTime） */
    @TableField("create_time")
    private LocalDateTime createTime;
}