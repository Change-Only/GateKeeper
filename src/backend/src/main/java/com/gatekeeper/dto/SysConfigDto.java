package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 系统参数配置 DTO — sys-config 页面入参/出参对象
 *
 * <p>字段命名严格对齐 sys_config 表与原型枚举字典。{@code configKey} 全局唯一
 * （DB 唯一键 uk_config_key），service 层会进行存在性与重复性校验。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Data
public class SysConfigDto {

    /** 主键ID（创建时为空，更新/删除必填） */
    private Long id;

    /** 配置键（全局唯一） */
    @NotBlank(message = "配置键不能为空")
    @Size(max = 128, message = "配置键长度不能超过128")
    private String configKey;

    /** 配置值 */
    @Size(max = 1024, message = "配置值长度不能超过1024")
    private String configValue;

    /** 配置分组：SECURITY/GATEWAY/LOG/DEFAULT */
    @NotBlank(message = "配置分组不能为空")
    @Size(max = 32, message = "配置分组长度不能超过32")
    private String configGroup;

    /** 配置名称 */
    @Size(max = 128, message = "配置名称长度不能超过128")
    private String configName;

    /** 1=敏感配置(响应脱敏), 0=普通 */
    private Integer sensitive;

    /** 1=内置不可删除, 0=可删除（仅内置种子用，前端一般不传） */
    private Integer builtIn;

    /** 备注 */
    @Size(max = 512, message = "备注长度不能超过512")
    private String remark;
}
