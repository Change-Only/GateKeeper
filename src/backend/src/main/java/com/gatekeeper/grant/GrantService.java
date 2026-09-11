package com.gatekeeper.grant;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.entity.AppApiGrant;

import java.time.LocalDate;
import java.util.List;

/**
 * 授权域服务接口 — T04-A 授权（审批流）核心能力
 *
 * <p>负责 app_api_grant 的查询、创建（待审批）、审批通过/驳回/撤销/延期，
 * 以及兼容旧权限模型的批量/按分组授权与按应用+接口撤销。</p>
 */
public interface GrantService extends IService<AppApiGrant> {

    /**
     * 按条件查询授权列表（仅查询，无副作用）。
     *
     * @param appId   应用 ID（可空）
     * @param apiId   接口 ID（可空）
     * @param envCode 环境编码（可空）
     * @param status  状态（可空）
     * @return 授权列表
     */
    List<AppApiGrant> listGrants(Long appId, Long apiId, String envCode, Integer status);

    /**
     * 按主键查询授权详情（不存在抛 404）。
     *
     * @param id 授权 ID
     * @return 授权实体
     */
    AppApiGrant getGrant(Long id);

    /**
     * 按主键查询授权详情的别名（与 {@link #getGrant(Long)} 等价）。
     * 保留该别名以兼容 Controller 端 {@code detail(id)} 调用约定。
     *
     * @param id 授权 ID
     * @return 授权实体
     */
    default AppApiGrant detail(Long id) {
        return getGrant(id);
    }

    /**
     * 创建授权（默认 status=0 待审批；app+api+env 唯一约束）。
     *
     * @param grant 授权实体（appId / apiId / envCode 必填，envCode 缺省 prod）
     * @return 创建后的授权实体
     * @throws com.gatekeeper.exception.GatewayException appId/apiId 缺失或已存在待审批/生效授权
     */
    AppApiGrant createGrant(AppApiGrant grant);

    /**
     * 审批通过：0→1，写入审批人/审批时间/审批意见。
     *
     * @param id          授权 ID
     * @param auditRemark 审批意见
     * @param auditorId   审批人 ID（可为 null）
     * @param auditorName 审批人姓名（可为 null）
     * @return 更新后的授权实体
     */
    AppApiGrant approve(Long id, String auditRemark, Long auditorId, String auditorName);

    /**
     * 审批驳回：0→4，必须填写审批意见。
     *
     * @param id          授权 ID
     * @param auditRemark 审批意见（必填）
     * @param auditorId   审批人 ID（可为 null）
     * @param auditorName 审批人姓名（可为 null）
     * @return 更新后的授权实体
     */
    AppApiGrant reject(Long id, String auditRemark, Long auditorId, String auditorName);

    /**
     * 撤销：1→3，必须填写撤销原因。
     *
     * @param id           授权 ID
     * @param revokeReason 撤销原因（必填）
     * @param auditorId    审批人 ID（可为 null）
     * @param auditorName  审批人姓名（可为 null）
     * @return 更新后的授权实体
     */
    AppApiGrant revoke(Long id, String revokeReason, Long auditorId, String auditorName);

    /**
     * 延期：从 status=1/2 延长 validTo（过期可复活为 1）。
     *
     * @param id     授权 ID
     * @param validTo 新的失效日期（可空，缺省 +30 天）
     * @return 更新后的授权实体
     */
    AppApiGrant renew(Long id, LocalDate validTo);

    /**
     * 查询待审批授权（status=0，可按 env 过滤）。
     *
     * @param envCode 环境编码（可空）
     * @return 待审批授权列表
     */
    List<AppApiGrant> listPending(String envCode);

    /**
     * 批量创建授权（兼容旧 /permission/batch）。
     *
     * @param appId  应用 ID
     * @param apiIds 接口 ID 列表
     */
    void batchCreate(Long appId, List<Long> apiIds);

    /**
     * 按接口分组创建授权（兼容旧 /permission/grant-by-group）。
     *
     * @param appId   应用 ID
     * @param groupId 接口分组 ID
     */
    void createByGroup(Long appId, Long groupId);

    /**
     * 按 应用+接口 撤销其生效/待审批中的授权（兼容旧 DELETE /permission）。
     *
     * @param appId 应用 ID
     * @param apiId 接口 ID
     */
    void revokeByAppAndApi(Long appId, Long apiId);
}
