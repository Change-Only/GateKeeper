package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * 数据范围项 DTO — 单个范围条目（业务线 / 环境 / 接口分组）
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Data
public class DataScopeItemDto {

    /** 范围类型：BIZ_LINE=业务线, ENV=环境, API_GROUP=接口分组 */
    @NotBlank(message = "范围类型不能为空")
    @Pattern(regexp = "BIZ_LINE|ENV|API_GROUP", message = "scopeType 必须为 BIZ_LINE/ENV/API_GROUP")
    private String scopeType;

    /** 范围值：业务线ID / 环境编码 / 接口分组ID */
    @NotBlank(message = "范围值不能为空")
    private String scopeValue;
}
