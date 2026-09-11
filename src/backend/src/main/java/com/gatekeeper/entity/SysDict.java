package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据字典表（sys_dict）
 *
 * <p>前端下拉框与后端校验都从此读，禁止在代码里硬编码枚举。
 * built_in=1 为内置字典，不可删除。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("sys_dict")
public class SysDict {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 字典编码（原型 dictCode，全局唯一） */
    private String dictCode;

    /** 字典名称（原型 dictName） */
    private String dictName;

    /** 1=内置不可删除, 0=可删除（原型 builtIn） */
    private Integer builtIn;

    /** 1=启用, 0=停用（原型 status） */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}