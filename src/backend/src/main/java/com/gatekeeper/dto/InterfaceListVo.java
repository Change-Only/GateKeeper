package com.gatekeeper.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口列表行 VO — T03b 升级后的 /interface/list 展示对象
 *
 * <p>在存量 {@code ApiInterface} 字段基础上，补充跨表冗余展示列：
 * {@code groupName}（来自 api_group）、{@code lineName}（来自 biz_line），
 * 以及发布生命周期 {@code publishStatus} 与 {@code currentVersion}。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Data
public class InterfaceListVo {

    /** 接口ID */
    private Long id;

    /** 接口编码 如 order.create */
    private String apiCode;

    /** 接口名称 */
    private String interfaceName;

    /** 网关对外暴露路径 */
    private String interfacePath;

    /** 请求方法 */
    private String requestMethod;

    /** 所属分组ID */
    private Long groupId;

    /** 所属分组名（JOIN api_group） */
    private String groupName;

    /** 业务线ID */
    private Long lineId;

    /** 业务线名（JOIN biz_line） */
    private String lineName;

    /** 网关开关：1=启用 0=停用 */
    private Integer status;

    /** 发布状态：0=草稿,1=待审核,2=已发布,3=已弃用,4=已下线 */
    private Integer publishStatus;

    /** 当前版本号 */
    private String currentVersion;

    /** 可见性：1=内部 2=对外 */
    private Integer visibility;

    /** 负责人姓名 */
    private String ownerName;

    /** 传输安全：NONE/TLS/MTLS */
    private String transportSecurity;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /*
     * 🔴 以下字段是 2026-09-13 补的「列表 VO ⊇ 实体」契约缺口，勿删：
     *
     * T03b 把 /interface/list 的返回从 ApiInterface 实体换成 InterfaceListVo 时，
     * VO 只挑了「展示用」字段，与实体存在差集。这带来两类缺陷：
     *
     * ① 列表列空白：前端 ApiList.vue 的列定义仍按实体字段取名，
     *    「后端地址 / 超时(ms) / 创建时间」三列**永远空白**（实测：接口有数据、单元格为空）。
     *
     * ② 编辑静默清空字段（更严重，2026-09-13 实测复现）：
     *    ApiList.openEdit(row) 直接以列表行做编辑表单初值，CrudDialog 对 fields 中
     *    「初值为 undefined」的项补空串，提交时把空串写回——MyBatis-Plus updateById 的
     *    默认策略 NOT_NULL 只忽略 null、**不忽略空串**，于是
     *    「打开编辑 → 什么都不改 → 点确定」就会把 requestParamType / description 清成 ''。
     *    实测：DB 由 request_param_type='JSON' / description='CDP 复现用…' 变为两列皆 ''。
     *
     * 因此口径修正为：**列表 VO 必须覆盖实体的全部可编辑字段**（只加不减），
     * 编辑回填才不会缺项。所有新增字段名与 ApiInterface 属性同名，故 toListVo 里现成的
     * BeanUtils.copyProperties(r, vo) 会自动填充，服务层零改动。
     */

    /** 入参类型（JSON/FORM/QUERY）—— 编辑表单必填项，缺则被清空 */
    private String requestParamType;

    /** 接口描述 —— 编辑表单可填项，缺则被清空 */
    private String description;

    /** 负责人ID */
    private Long ownerId;

    /** 是否鉴权：1=需要 0=不需要 */
    private Integer authRequired;

    /** SLA 等级说明 */
    private String sla;

    /** 标签（逗号分隔） */
    private String tags;

    /** 已授权应用数 */
    private Integer grantCount;

    /** 后端真实服务地址（网关转发目标） */
    private String backendUrl;

    /** 转发超时（毫秒） */
    private Integer timeoutMs;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
