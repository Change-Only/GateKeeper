package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口参数定义表（api_param）— 入参/响应/错误码 元数据
 *
 * <p>param_type: 1=HEADER, 2=QUERY, 3=BODY, 4=RESPONSE, 5=ERROR_CODE。
 * parent_id 支持嵌套结构（如 items.skuId）。
 *
 * <p>注：列名 {@code sensitive} 在 MySQL 8 中是保留字，字段映射使用反引号包裹的列名。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("api_param")
public class ApiParam {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口ID（原型 apiId） */
    private Long apiId;

    /** 参数类型：1=HEADER,2=QUERY,3=BODY,4=RESPONSE,5=ERROR_CODE（原型 paramType） */
    private Integer paramType;

    /** 父级参数ID，0=顶层，支持嵌套（原型 parentId） */
    private Long parentId;

    /** 字段名 / 错误码KEY（原型 fieldName） */
    private String fieldName;

    /** 字段类型：string/int/number/array/object/bool（原型 fieldType） */
    private String fieldType;

    /** 1=必填, 0=选填（原型 required） */
    private Integer required;

    /** 示例值（原型 example） */
    private String example;

    /** 错误码，param_type=5 时有效（原型 errorCode） */
    private String errorCode;

    /** HTTP状态码，param_type=5 时有效（原型 httpStatus） */
    private Integer httpStatus;

    /** 1=敏感字段, 0=否（原型 sensitive，MySQL 8 保留字需反引号） */
    @TableField("`sensitive`")
    private Integer sensitive;

    /** 加解密/脱敏规则 SYMMETRIC/MASK/NONE（原型 encryptRule） */
    private String encryptRule;

    /** 排序 */
    private Integer sortOrder;

    /** 字段说明（原型 desc） */
    private String description;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}