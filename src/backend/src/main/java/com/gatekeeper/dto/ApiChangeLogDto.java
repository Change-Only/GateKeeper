package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 接口变更历史 DTO — 接口变更历史页面入参/出参对象
 *
 * <p>T03b 接口生命周期子资源之一（追加型，只读）。对应 api_change_log 表，
 * 记录接口每次变更（CREATE/UPDATE/PUBLISH/OFFLINE/DELETE），旧值/新值以字符串存储。</p>
 *
 * <p>字段命名严格对齐原型 MOCK.apiChangeLogs 字典：
 * <ul>
 *   <li>changeType: CREATE/UPDATE/PUBLISH/OFFLINE/DELETE</li>
 *   <li>createTime 映射自数据库列 create_time（entity 已用 @TableField 标注）</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Data
public class ApiChangeLogDto {

    /** 主键ID（追加时自动生成） */
    private Long id;

    /** 接口ID（原型 apiId） */
    @NotNull(message = "接口ID不能为空")
    private Long apiId;

    /** 变更类型：CREATE/UPDATE/PUBLISH/OFFLINE/DELETE（原型 changeType） */
    @NotBlank(message = "变更类型不能为空")
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

    /** 变更时间（原型 createTime，映射自 create_time） */
    private LocalDateTime createTime;
}
