package com.gatekeeper.vo;

import com.gatekeeper.entity.ApiParam;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用「接口文档」数据 VO — T16-2
 *
 * <p>需求：「应用管理中增加接口文档导出，<b>只导出有权限的接口</b>」。</p>
 *
 * <p>本 VO 只负责<b>数据</b>；Markdown 正文由前端 {@code utils/interfaceDoc.js} 生成
 * （与 T14 接入文档一致：正文只在 JS 侧定义一次，避免后端模板与前端渲染两套口径）。</p>
 *
 * <p><b>「有权限」的判定</b>（三者同时满足）：
 * <ol>
 *   <li>授权行 {@code app_api_grant.status = 1}（已生效；待审批/已驳回/已过期/已撤销一律排除）；</li>
 *   <li>授权在有效期内（{@code valid_from} / {@code valid_to} 允许为 NULL = 不限制）；</li>
 *   <li>目标接口存在且 {@code api_interface.status = 1}（网关启用；停用的接口导出出去只会误导调用方）。</li>
 * </ol>
 * 被排除的两类单独计数（{@link #danglingCount} / {@link #disabledCount}），
 * 避免"导出的条数少于授权条数"变成一个无法解释的谜。</p>
 *
 * @author GateKeeper
 * @since T16-2 (2026-09-15)
 */
@Data
public class AppInterfaceDocVo {

    /** 应用 ID */
    private Long appId;

    /** 应用名称 */
    private String appName;

    /** 应用 AppKey（网关鉴权用，文档里要给调用方） */
    private String appKey;

    /** 应用状态：1=启用 0=停用 2=已过期 */
    private Integer appStatus;

    /** 实际导出的接口条数（= items.size()） */
    private Integer total;

    /** 被剔除的「悬空授权」数：授权指向的接口记录已不存在 */
    private Integer danglingCount;

    /** 被剔除的「未启用接口」数：接口存在但 status != 1 */
    private Integer disabledCount;

    /** 接口明细（按分组名、接口路径稳定排序） */
    private List<Item> items = new ArrayList<>();

    /** 单条接口明细（接口元数据 + 授权信息 + 参数定义） */
    @Data
    public static class Item {

        // ---------- 授权信息 ----------
        /** 授权行 ID */
        private Long grantId;
        /** 环境编码（prod / test …） */
        private String envCode;
        /** 授权 QPS 上限，0=不限 */
        private Integer qpsLimit;
        /** 授权日配额，0=不限 */
        private Long dailyQuota;
        /** 生效日期（可空） */
        private LocalDate validFrom;
        /** 失效日期（可空） */
        private LocalDate validTo;

        // ---------- 接口元数据 ----------
        /** 接口 ID */
        private Long interfaceId;
        /** 接口编码，如 order.create */
        private String apiCode;
        /** 接口名称 */
        private String interfaceName;
        /** 网关对外路径 */
        private String interfacePath;
        /** 请求方法 GET/POST/PUT/DELETE */
        private String requestMethod;
        /** 入参类型 JSON/FORM/QUERY */
        private String requestParamType;
        /** 接口描述 */
        private String description;
        /** 所属分组 ID */
        private Long groupId;
        /** 所属分组名 */
        private String groupName;
        /** 标签，逗号分隔 */
        private String tags;
        /** SLA 承诺 */
        private String sla;
        /** 转发超时（毫秒） */
        private Integer timeoutMs;
        /** 当前版本号 */
        private String currentVersion;
        /** 鉴权要求：1=需鉴权 0=免鉴权 */
        private Integer authRequired;

        // ---------- 参数定义 ----------
        /**
         * 参数明细（{@code api_param}）。
         * paramType：1=HEADER, 2=QUERY, 3=BODY, 4=RESPONSE, 5=ERROR_CODE。
         * 前端按 paramType 分组渲染成多张表。
         */
        private List<ApiParam> params = new ArrayList<>();
    }
}
