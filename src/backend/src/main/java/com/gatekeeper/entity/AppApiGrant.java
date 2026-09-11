package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 应用接口授权表（app_api_grant）— 带审批/有效期/环境的授权
 *
 * <p>替代存量 app_api_permission（无审批、无有效期、无环境）。
 * status 状态机（架构 D3）：0=待审批, 1=已生效, 2=已过期, 3=已撤销, 4=已驳回。
 * 网关校验核心结论：审批中/已驳回/已过期/已撤销一律拒绝；只放行 status=1 且在有效期内的授权。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("app_api_grant")
public class AppApiGrant {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用ID（原型 appId） */
    private Long appId;

    /** 接口ID（原型 apiId） */
    private Long apiId;

    /** 环境编码（原型 envCode，默认 prod） */
    private String envCode;

    /** 授权QPS，0=不限（原型 qpsLimit） */
    private Integer qpsLimit;

    /** 授权日配额，0=不限（原型 dailyQuota） */
    private Long dailyQuota;

    /** 0=待审批, 1=已生效, 2=已过期, 3=已撤销, 4=已驳回（原型 status / dict grant_status） */
    private Integer status;

    /** 生效日期（原型 validFrom，DATE 类型） */
    private LocalDate validFrom;

    /** 失效日期（原型 validTo，DATE 类型） */
    private LocalDate validTo;

    /** 申请理由（原型 grantReason） */
    private String grantReason;

    /** 申请人ID */
    private Long applicantId;

    /** 申请人姓名（原型 applicantName） */
    private String applicantName;

    /** 审批人ID */
    private Long auditorId;

    /** 审批人姓名（原型 auditorName） */
    private String auditorName;

    /** 审批时间（原型 auditTime） */
    private LocalDateTime auditTime;

    /** 审批意见 */
    private String auditRemark;

    /** 撤销原因 */
    private String revokeReason;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}