package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
/**
 * 接口表（api_interface）— 网关代理的目标接口定义
 * 定义网关对外暴露路径与后端真实服务地址的映射，是网关转发与权限控制的核心实体。
 *
 * <p>T01 已为 api_interface 增加 12 列（api_code / line_id / owner_* / visibility /
 * auth_required / publish_status / current_version / sla / tags / transport_security /
 * grant_count），本类在 T03b 补齐对应字段，供接口列表 JOIN 展示与详情聚合使用。
 *
 * <p>状态语义（双轨，铁律「加新列承载原型语义 + 存量列不动」）：
 * <ul>
 *   <li>{@code status}：网关开关，1=启用 0=停用</li>
 *   <li>{@code publishStatus}：发布生命周期，0=草稿 1=待审核 2=已发布 3=已弃用 4=已下线</li>
 * </ul>
 */
@TableName("api_interface")
public class ApiInterface {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口名称 */
    private String interfaceName;

    /** 网关对外暴露路径（/gateway/ 开头） */
    private String interfacePath;

    /** 请求方法（GET/POST/PUT/DELETE） */
    private String requestMethod;

    /** 入参类型（JSON/FORM/QUERY） */
    private String requestParamType;

    /** 所属分组ID（关联 api_group.id） */
    private Long groupId;

    /** 后端真实服务地址（网关转发目标） */
    private String backendUrl;

    /** 接口状态（1=启用 0=停用） */
    private Integer status;

    /** 转发超时时间（毫秒） */
    private Integer timeoutMs;

    /** 接口描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    // ==================== T01 ALTER 新增列（T03b 补齐映射） ====================

    /** 接口编码 如 order.create（原型 apiCode，唯一） */
    private String apiCode;

    /** 业务线ID（原型 lineId） */
    private Long lineId;

    /** 负责人用户ID */
    private Long ownerId;

    /** 负责人姓名（原型 ownerName） */
    private String ownerName;

    /** 可见性：1=内部, 2=对外公开（原型 visibility） */
    private Integer visibility;

    /** 鉴权要求：1=需鉴权, 0=免鉴权（原型 authRequired） */
    private Integer authRequired;

    /** 发布状态：0=草稿,1=待审核,2=已发布,3=已弃用,4=已下线（原型 api_status） */
    private Integer publishStatus;

    /** 当前版本号（冗余自 api_version.is_current） */
    private String currentVersion;

    /** SLA 承诺 如 99.9%, P99<200ms（原型 sla） */
    private String sla;

    /** 标签，逗号分隔 如 核心链路,只读（原型 tags 数组归一化） */
    private String tags;

    /** 传输安全：NONE/TLS/MTLS（原型 transportSecurity） */
    private String transportSecurity;

    /** 授权数（统计冗余） */
    private Integer grantCount;
}
