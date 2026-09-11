package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 数据字典 DTO — sys-dict 页面字典头入参/出参对象
 *
 * <p>{@code dictCode} 全局唯一（DB 唯一键 uk_dict_code），service 层会进行存在性校验。
 * 字典项（items）不随字典头一起提交，通过 DictController 的 D8/D9 单独维护。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Data
public class SysDictDto {

    /** 主键ID（创建时为空，更新必填） */
    private Long id;

    /** 字典编码（全局唯一） */
    @NotBlank(message = "字典编码不能为空")
    @Size(max = 64, message = "字典编码长度不能超过64")
    private String dictCode;

    /** 字典名称 */
    @NotBlank(message = "字典名称不能为空")
    @Size(max = 128, message = "字典名称长度不能超过128")
    private String dictName;

    /** 1=启用, 0=停用 */
    private Integer status;

    /** 备注 */
    @Size(max = 255, message = "备注长度不能超过255")
    private String remark;
}
