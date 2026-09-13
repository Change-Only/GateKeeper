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
     * 🔴 以下三个字段是 2026-09-13 补的「前端列契约」缺口，勿删：
     * T03b 把 /interface/list 的返回从 ApiInterface 实体换成 InterfaceListVo 时，
     * 前端 ApiList.vue 的列定义（T05 Phase 1 时代）仍按实体字段取名，于是
     * 「后端地址 / 超时(ms) / 创建时间」三列**永远空白**（实测：接口有数据、单元格为空）。
     * 三个名字与 ApiInterface 实体属性同名，故 toListVo 里现成的
     * BeanUtils.copyProperties(r, vo) 会自动填充，服务层零改动。
     * 按项目铁律「只加不减」，此处只补字段、不动既有字段。
     */

    /** 后端真实服务地址（网关转发目标） */
    private String backendUrl;

    /** 转发超时（毫秒） */
    private Integer timeoutMs;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
