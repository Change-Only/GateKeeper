package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 业务线 DTO — 业务线管理页面入参/出参对象
 *
 * <p>T03a 业务线主数据的对外传输对象。{@code lineCode} 全局唯一（DB 唯一键
 * {@code uk_bizline_code}），service 层会进行存在性与重复性校验。</p>
 *
 * <p>字段命名严格对齐原型枚举字典 §五（bizLines）。</p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@Data
public class BizLineDto {

    /** 主键ID（创建时为空，更新/删除必填） */
    private Long id;

    /** 业务线编码（如 trade/pay/user），全局唯一 */
    @NotBlank(message = "业务线编码不能为空")
    @Size(max = 64, message = "业务线编码长度不能超过64")
    private String lineCode;

    /** 业务线中文名 */
    @NotBlank(message = "业务线名称不能为空")
    @Size(max = 128, message = "业务线名称长度不能超过128")
    private String lineName;

    /** 负责人姓名（手填，非用户表关联） */
    @Size(max = 64, message = "负责人姓名长度不能超过64")
    private String ownerName;

    /** 成员数（手填统计冗余，非实时） */
    private Integer memberCount;

    /** 状态：0=停用, 1=启用 */
    private Integer status;

    /** 备注 */
    @Size(max = 512, message = "备注长度不能超过512")
    private String remark;

    /** 创建时间（响应字段） */
    private LocalDateTime createdAt;

    /** 更新时间（响应字段） */
    private LocalDateTime updatedAt;
}
