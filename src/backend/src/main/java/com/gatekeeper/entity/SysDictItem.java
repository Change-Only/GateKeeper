package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 数据字典项表（sys_dict_item）
 *
 * <p>逻辑外键关联 sys_dict.dict_code（dict_code+item_value 唯一）。
 * 缓存：gk:dict:{dictCode} 存 List&lt;DictItem&gt; JSON（架构 D7）。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("sys_dict_item")
public class SysDictItem {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 字典编码（逻辑关联 sys_dict.dict_code） */
    private String dictCode;

    /** 字典项值（原型 itemValue） */
    private String itemValue;

    /** 字典项标签（原型 itemLabel） */
    private String itemLabel;

    /** 排序（原型 sort） */
    private Integer sortOrder;

    /** 1=启用, 0=停用（原型 status） */
    private Integer status;
}