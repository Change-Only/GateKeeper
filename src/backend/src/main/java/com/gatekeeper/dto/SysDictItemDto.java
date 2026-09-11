package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 数据字典项 DTO — sys-dict 页面字典项入参/出参对象
 *
 * <p>{@code dictCode} + {@code itemValue} 唯一（DB 唯一键），service 层会校验。
 * 排序与状态语义：0=停用, 1=启用（对齐原型枚举字典）。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Data
public class SysDictItemDto {

    /** 主键ID（创建时为空，更新必填） */
    private Long id;

    /** 归属字典编码 */
    @NotBlank(message = "字典编码不能为空")
    @Size(max = 64, message = "字典编码长度不能超过64")
    private String dictCode;

    /** 字典项值 */
    @NotBlank(message = "字典项值不能为空")
    @Size(max = 64, message = "字典项值长度不能超过64")
    private String itemValue;

    /** 字典项标签 */
    @NotBlank(message = "字典项标签不能为空")
    @Size(max = 128, message = "字典项标签长度不能超过128")
    private String itemLabel;

    /** 排序 */
    private Integer sortOrder;

    /** 1=启用, 0=停用 */
    private Integer status;
}
