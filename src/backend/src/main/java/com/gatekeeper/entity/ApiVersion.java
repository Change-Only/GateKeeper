package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 接口版本表（api_version）
 *
 * <p>每个接口可注册多个版本，is_current=1 标记当前默认版本。
 * gray_ratio 控制灰度流量比例（0-100），配合 is_current 由 VersionRouteHandler 在数据面分流（架构 D2）。
 * status 复用字典 api_status：1=生效中, 2=已弃用, 3=已下线。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("api_version")
public class ApiVersion {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口ID（原型 apiId） */
    private Long apiId;

    /** 版本号 v1/v2（原型 version） */
    private String version;

    /** 1=生效中, 2=已弃用, 3=已下线（原型 status） */
    private Integer status;

    /** 1=当前默认版本, 0=非默认（原型 isCurrent） */
    private Integer isCurrent;

    /** 灰度流量百分比 0-100（原型 grayRatio） */
    private Integer grayRatio;

    /** 版本变更说明（原型 changeLog） */
    private String changeLog;

    /** 弃用时间（原型 deprecateTime，DATE 类型） */
    private LocalDate deprecateTime;

    /** 计划下线时间（原型 offlinePlanTime，DATE 类型） */
    private LocalDate offlinePlanTime;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}