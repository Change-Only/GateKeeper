package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务线表（biz_line）— 一级组织维度
 *
 * <p>app / api_interface / api_group / sys_user 均挂 line_id。
 * 迁移默认业务线 id=100，line_code='common'，承载存量应用/接口的历史归属。
 *
 * @author GateKeeper
 * @since T01 (APIM V2)
 */
@Data
@TableName("biz_line")
public class BizLine {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务线编码（原型 lineCode，全局唯一） */
    private String lineCode;

    /** 业务线名称（原型 lineName） */
    private String lineName;

    /** 负责人姓名（原型 ownerName） */
    private String ownerName;

    /** 成员数量（原型 memberCount，统计冗余） */
    private Integer memberCount;

    /** 状态：1=启用, 0=停用（原型 status） */
    private Integer status;

    /** 备注（原型 remark） */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}