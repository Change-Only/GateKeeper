package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
/**
 * 接口分组表（api_group）— 接口的分组管理，支持树形多层级
 * 通过 parentId 自关联形成分组树，children 为内存中构建的子分组列表（非表字段）
 */
@TableName("api_group")
public class ApiGroup {
    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分组名称 */
    private String groupName;

    /** 父分组ID（NULL=顶级分组） */
    private Long parentId;

    /** 分组描述 */
    private String description;

    /** 排序值（越小越靠前） */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 子分组（树形结构，非表字段） */
    @TableField(exist = false)
    private List<ApiGroup> children;
}
