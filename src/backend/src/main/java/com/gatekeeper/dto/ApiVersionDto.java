package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 接口版本 DTO — 接口版本管理页面入参/出参对象
 *
 * <p>T03b 接口生命周期子资源之一。对应 api_version 表，每个接口可注册多个版本，
 * is_current=1 标记当前默认版本（同一接口 EXACTLY ONE current），
 * gray_ratio 控制灰度流量比例（0-100）。</p>
 *
 * <p>字段命名严格对齐原型 MOCK.apiVersions 字典：
 * <ul>
 *   <li>status: 1=生效中, 2=已弃用, 3=已下线</li>
 *   <li>isCurrent: 1=当前默认版本, 0=非默认</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Data
public class ApiVersionDto {

    /** 主键ID（创建时为空，更新/删除必填） */
    private Long id;

    /** 接口ID（原型 apiId） */
    @NotNull(message = "接口ID不能为空")
    private Long apiId;

    /** 版本号 v1/v2（原型 version） */
    @NotBlank(message = "版本号不能为空")
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

    /** 创建时间（响应字段） */
    private LocalDateTime createdAt;

    /** 更新时间（响应字段） */
    private LocalDateTime updatedAt;
}
