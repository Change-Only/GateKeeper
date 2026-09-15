package com.gatekeeper.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 接口参数定义 DTO — 接口参数管理页面入参/出参对象
 *
 * <p>T03b 接口生命周期子资源之一。对应 api_param 表，支持 HEADER/QUERY/BODY/RESPONSE/ERROR_CODE
 * 五类参数，并通过 parent_id 支撑嵌套结构（如 items.skuId）。</p>
 *
 * <p>字段命名严格对齐原型 MOCK.apiParams 字典：
 * <ul>
 *   <li>paramType: 1=HEADER,2=QUERY,3=BODY,4=RESPONSE,5=ERROR_CODE</li>
 *   <li>required: 1=必填, 0=选填</li>
 *   <li>sensitive: 1=敏感字段, 0=否（MySQL 8 保留字，entity 已用反引号包裹列名）</li>
 * </ul></p>
 *
 * <p>children 用于 /tree 接口返回嵌套结构（root = parentId 0）。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Data
public class ApiParamDto {

    /** 主键ID（创建时为空，更新/删除必填） */
    private Long id;

    /** 接口ID（原型 apiId） */
    @NotNull(message = "接口ID不能为空")
    private Long apiId;

    /** 参数类型：1=HEADER,2=QUERY,3=BODY,4=RESPONSE,5=ERROR_CODE（原型 paramType） */
    @NotNull(message = "参数类型不能为空")
    private Integer paramType;

    /** 父级参数ID，0=顶层，支持嵌套（原型 parentId） */
    private Long parentId;

    /** 字段名 / 错误码KEY（原型 fieldName） */
    @NotBlank(message = "字段名不能为空")
    private String fieldName;

    /** 字段类型：string/int/number/array/object/bool（原型 fieldType） */
    private String fieldType;

    /** 1=必填, 0=选填（原型 required） */
    private Integer required;

    /** 示例值（原型 example） */
    private String example;

    /** 错误码，paramType=5 时有效（原型 errorCode） */
    private String errorCode;

    /** HTTP状态码，paramType=5 时有效（原型 httpStatus） */
    private Integer httpStatus;

    /** 1=敏感字段, 0=否（原型 sensitive） */
    private Integer sensitive;

    /** 加解密/脱敏规则 SYMMETRIC/MASK/NONE（原型 encryptRule） */
    private String encryptRule;

    /** 排序 */
    private Integer sortOrder;

    /** 字段说明（原型 description） */
    private String description;

    /** 子参数（仅 /tree 返回，原型嵌套结构） */
    private List<ApiParamDto> children;

    /** 创建时间（响应字段） */
    private LocalDateTime createdAt;

    /** 更新时间（响应字段） */
    private LocalDateTime updatedAt;

    /**
     * 本条参数的内容字段是否被掩码（T17，仅响应字段，不会被写库）。
     *
     * <p>{@code true} 时 {@link #fieldName}/{@link #example}/{@link #description}
     * 为固定掩码 {@code ****}，而非真实契约内容。结构字段
     * （paramType/fieldType/required/errorCode/httpStatus/sensitive/encryptRule/sortOrder）
     * <b>不受影响</b>，仍返回真值。</p>
     *
     * <p>前端据此渲染锁定态；批量保存时若带掩码会被后端 <b>400 拒绝</b>
     * （全量替换语义下掩码会覆盖不可见字段，见 docs/CONTRACTS §18）。</p>
     */
    private Boolean masked;
}
