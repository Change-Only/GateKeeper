package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.entity.App;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.ApiParamMapper;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.mapper.AppMapper;
import com.gatekeeper.service.AppInterfaceDocService;
import com.gatekeeper.vo.AppInterfaceDocVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 应用「接口文档」数据服务实现 — T16-2
 *
 * <h3>「有权限」的判定（与网关口径对齐，别自行放宽）</h3>
 * <ol>
 *   <li>{@code app_api_grant.status = 1}（已生效）。
 *       <b>待审批(0)/已过期(2)/已撤销(3)/已驳回(4) 一律不导出</b> ——
 *       网关 {@code PermissionHandler} 对这几态也是直接拒，导出了就是骗调用方。</li>
 *   <li>授权在有效期内：{@code valid_from <= today <= valid_to}，两者均可为 NULL（NULL=不限制）。</li>
 *   <li>接口存在且 {@code api_interface.status = 1}（网关启用）。</li>
 * </ol>
 *
 * <p>被排除的两类<b>分别计数</b>（dangling / disabled），
 * 让「导出条数 < 授权条数」这件事可解释，而不是让人对着数字猜。</p>
 *
 * <h3>为什么用 hasGrant 而非直接 JOIN</h3>
 * <p>本仓 mapper 层没有 XML（全部 MyBatis-Plus Wrapper），
 * 且 {@code api_param} 是"一条接口 N 行"的子表 —— 用 JOIN 会把结果集揉成笛卡尔积，
 * 还要在 Java 侧再去重。故按 授权 → 接口 → 分组 → 参数 四步批量查询后在内存拼装，
 * 每步都是单条 SQL（`IN` 批量），不会产生 N+1。</p>
 *
 * @author GateKeeper
 * @since T16-2 (2026-09-15)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppInterfaceDocServiceImpl implements AppInterfaceDocService {

    /** {@code app_api_grant.status}：1=已生效 */
    static final int GRANT_STATUS_ACTIVE = 1;

    /** {@code api_interface.status}：1=启用 */
    static final int INTERFACE_STATUS_ENABLED = 1;

    private final AppMapper appMapper;
    private final AppApiGrantMapper grantMapper;
    private final ApiInterfaceMapper apiInterfaceMapper;
    private final ApiGroupMapper apiGroupMapper;
    private final ApiParamMapper apiParamMapper;

    @Override
    public AppInterfaceDocVo build(Long appId) {
        if (appId == null) {
            throw GatewayException.badRequest("应用 ID 不能为空");
        }
        App app = appMapper.selectById(appId);
        if (app == null) {
            throw GatewayException.badRequest("应用不存在: " + appId);
        }

        AppInterfaceDocVo vo = new AppInterfaceDocVo();
        vo.setAppId(app.getId());
        vo.setAppName(app.getAppName());
        vo.setAppKey(app.getAppKey());
        vo.setAppStatus(app.getStatus());
        vo.setDanglingCount(0);
        vo.setDisabledCount(0);
        vo.setTotal(0);

        // ① 已生效且在有效期内的授权（valid_from / valid_to 为 NULL 视为不限制）
        LocalDate today = LocalDate.now();
        List<AppApiGrant> grants = grantMapper.selectList(new QueryWrapper<AppApiGrant>()
                .eq("app_id", appId)
                .eq("status", GRANT_STATUS_ACTIVE)
                .and(w -> w.isNull("valid_from").or().le("valid_from", today))
                .and(w -> w.isNull("valid_to").or().ge("valid_to", today))
                .orderByAsc("env_code")
                .orderByAsc("api_id"));
        if (grants == null || grants.isEmpty()) {
            return vo;
        }

        // ② 批量取接口（**含停用的**：才能把「悬空」与「未启用」区分开）
        Set<Long> apiIds = new LinkedHashSet<>();
        for (AppApiGrant g : grants) {
            if (g.getApiId() != null) {
                apiIds.add(g.getApiId());
            }
        }
        Map<Long, ApiInterface> ifaceById = new HashMap<>();
        if (!apiIds.isEmpty()) {
            List<ApiInterface> ifaces = apiInterfaceMapper.selectList(
                    new QueryWrapper<ApiInterface>().in("id", apiIds));
            if (ifaces != null) {
                for (ApiInterface i : ifaces) {
                    ifaceById.put(i.getId(), i);
                }
            }
        }

        // ③ 分组名（一次批量取，避免逐个 selectById 的 N+1）
        Set<Long> groupIds = new LinkedHashSet<>();
        for (ApiInterface i : ifaceById.values()) {
            if (i.getGroupId() != null) {
                groupIds.add(i.getGroupId());
            }
        }
        Map<Long, String> groupNameById = new HashMap<>();
        if (!groupIds.isEmpty()) {
            List<ApiGroup> groups = apiGroupMapper.selectList(
                    new QueryWrapper<ApiGroup>().in("id", groupIds));
            if (groups != null) {
                for (ApiGroup grp : groups) {
                    groupNameById.put(grp.getId(), grp.getGroupName());
                }
            }
        }

        // ④ 参数明细（只取真正会导出的接口）
        Set<Long> exportIfaceIds = new LinkedHashSet<>();
        for (AppApiGrant g : grants) {
            ApiInterface i = g.getApiId() == null ? null : ifaceById.get(g.getApiId());
            if (i != null && isEnabled(i)) {
                exportIfaceIds.add(i.getId());
            }
        }
        Map<Long, List<ApiParam>> paramsByIface = new HashMap<>();
        if (!exportIfaceIds.isEmpty()) {
            List<ApiParam> allParams = apiParamMapper.selectList(
                    new QueryWrapper<ApiParam>().in("api_id", exportIfaceIds));
            if (allParams != null) {
                for (ApiParam p : allParams) {
                    paramsByIface.computeIfAbsent(p.getApiId(), k -> new ArrayList<>()).add(p);
                }
                for (List<ApiParam> list : paramsByIface.values()) {
                    list.sort(PARAM_ORDER);
                }
            }
        }

        // ⑤ 组装 + 计数
        List<AppInterfaceDocVo.Item> items = new ArrayList<>();
        int dangling = 0;
        int disabled = 0;
        for (AppApiGrant g : grants) {
            ApiInterface i = g.getApiId() == null ? null : ifaceById.get(g.getApiId());
            if (i == null) {
                dangling++;
                continue;
            }
            if (!isEnabled(i)) {
                disabled++;
                continue;
            }
            items.add(toItem(g, i, groupNameById.get(i.getGroupId()),
                    paramsByIface.getOrDefault(i.getId(), Collections.emptyList())));
        }

        // 稳定排序：先按分组名，再按接口路径（同一分组内路径相邻，便于阅读）
        items.sort(Comparator
                .comparing((AppInterfaceDocVo.Item it) -> it.getGroupName() == null ? "" : it.getGroupName())
                .thenComparing(it -> it.getInterfacePath() == null ? "" : it.getInterfacePath()));

        vo.setItems(items);
        vo.setTotal(items.size());
        vo.setDanglingCount(dangling);
        vo.setDisabledCount(disabled);
        log.debug("接口文档数据构建完成: appId={}, 生效授权={}, 导出={}, 悬空={}, 未启用={}",
                appId, grants.size(), items.size(), dangling, disabled);
        return vo;
    }

    /** 接口是否处于「网关启用」状态 */
    private boolean isEnabled(ApiInterface i) {
        return i.getStatus() != null && i.getStatus().intValue() == INTERFACE_STATUS_ENABLED;
    }

    /** 授权 + 接口 + 分组 + 参数 → 一行明细 */
    private AppInterfaceDocVo.Item toItem(AppApiGrant g, ApiInterface i, String groupName,
                                         List<ApiParam> params) {
        AppInterfaceDocVo.Item it = new AppInterfaceDocVo.Item();
        it.setGrantId(g.getId());
        it.setEnvCode(g.getEnvCode());
        it.setQpsLimit(g.getQpsLimit());
        it.setDailyQuota(g.getDailyQuota());
        it.setValidFrom(g.getValidFrom());
        it.setValidTo(g.getValidTo());

        it.setInterfaceId(i.getId());
        it.setApiCode(i.getApiCode());
        it.setInterfaceName(i.getInterfaceName());
        it.setInterfacePath(i.getInterfacePath());
        it.setRequestMethod(i.getRequestMethod());
        it.setRequestParamType(i.getRequestParamType());
        it.setDescription(i.getDescription());
        it.setGroupId(i.getGroupId());
        it.setGroupName(groupName);
        it.setTags(i.getTags());
        it.setSla(i.getSla());
        it.setTimeoutMs(i.getTimeoutMs());
        it.setCurrentVersion(i.getCurrentVersion());
        it.setAuthRequired(i.getAuthRequired());

        it.setParams(params == null ? new ArrayList<>() : new ArrayList<>(params));
        return it;
    }

    /**
     * 参数排序：先 paramType（HEADER→QUERY→BODY→RESPONSE→ERROR_CODE，与文档章节顺序一致），
     * 再 sort_order，最后 id。三者都可能为 NULL，统一用 nullsLast 兜住。
     *
     * <p>显式写出类型参数 {@code <ApiParam, Integer>}：链式 {@code thenComparing}
     * 对方法引用的泛型推断很容易失败（会报 "cannot infer type"），不要图省事删掉。</p>
     */
    private static final Comparator<ApiParam> PARAM_ORDER =
            Comparator.<ApiParam, Integer>comparing(ApiParam::getParamType,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ApiParam::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ApiParam::getId, Comparator.nullsLast(Comparator.naturalOrder()));
}
