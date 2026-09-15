package com.gatekeeper.gateway;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiGroupEncryptionConfig;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.gateway.dto.EffectiveGroupEncryption;
import com.gatekeeper.mapper.ApiGroupEncryptionConfigMapper;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分组级加解密「生效配置」解析器 — T15-1 的唯一解析口径
 *
 * <p><b>为什么必须收敛成一个组件</b>：分组级加解密有两处要用 ——
 * ① 网关 {@code EncryptionHandler} 决定实际加解密行为；
 * ② 前端分组加解密面板展示"我这个分组的生效配置（可能继承自父级）"。
 * 如果各写一份父链遍历，迟早出现「页面显示继承自 A 分组、网关实际按 B 加密」这种最难查的错位。
 * 与 T13 的 {@link EnvConfigResolver} 同一设计取向。</p>
 *
 * <p><b>三态语义</b>（本需求的核心，老表 boolean 表达不了）：
 * <ul>
 *   <li>{@code INHERIT} —— 本行等价于"未配置"，继续沿 {@code parent_id} 上溯；</li>
 *   <li>{@code ENABLED} —— 命中，终止上溯，采用该行配置；</li>
 *   <li>{@code DISABLED} —— 命中，终止上溯，且**明确不回退**应用级配置
 *       （用户语义是"这条链路就是不需要加解密"）。</li>
 * </ul></p>
 *
 * <p><b>返回值三态</b>：{@code null}（父链无配置，调用方回退应用级）/
 * {@code disabled=true}（显式关闭）/ {@code enabled=true}（采用配置）。</p>
 *
 * <p><b>防御</b>：分组树理论上可能成环（人工改库、脏数据），父链遍历带 visited 集合与最大深度，
 * 成环立即停止并 WARN，绝不无限循环。</p>
 *
 * @author GateKeeper
 * @since T15-1 (2026-09-15)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EncryptionConfigResolver {

    /** 分组父链最大遍历深度（正常分组树远小于此值，仅用于兜住环） */
    static final int MAX_GROUP_DEPTH = 32;

    private final ApiGroupEncryptionConfigMapper groupEncMapper;
    private final ApiGroupMapper apiGroupMapper;
    private final ApiInterfaceMapper apiInterfaceMapper;

    /**
     * 按接口 ID 解析分组级生效配置（网关侧入口）。
     *
     * <p>先由 {@code api_interface.group_id} 找到接口所属分组，再沿父链上溯。</p>
     *
     * @param interfaceId 接口 ID
     * @return 见类注释的「返回值三态」
     */
    public EffectiveGroupEncryption resolveByInterface(Long interfaceId) {
        if (interfaceId == null) {
            return null;
        }
        ApiInterface api = apiInterfaceMapper.selectById(interfaceId);
        if (api == null) {
            return null;
        }
        return resolveForGroup(api.getGroupId());
    }

    /**
     * 按分组 ID 解析生效配置（前端预览 / 网关共用）。
     *
     * @param groupId 起始分组 ID
     * @return 见类注释的「返回值三态」
     */
    public EffectiveGroupEncryption resolveForGroup(Long groupId) {
        if (groupId == null) {
            return null;
        }
        Set<Long> visited = new HashSet<>();
        List<String> chain = new ArrayList<>();
        Long cursor = groupId;
        int depth = 0;

        while (cursor != null && depth < MAX_GROUP_DEPTH) {
            if (!visited.add(cursor)) {
                log.warn("分组树成环，加解密继承解析提前终止: groupId={}, chain={}", groupId, chain);
                break;
            }
            ApiGroup group = apiGroupMapper.selectById(cursor);
            if (group == null) {
                break;
            }
            chain.add(group.getGroupName());

            ApiGroupEncryptionConfig hit = pickOwn(cursor);
            if (hit != null) {
                String mode = normalizeMode(hit.getMode());
                if (ApiGroupEncryptionConfig.MODE_INHERIT.equals(mode)) {
                    // 继承态：本行等价于未配置，继续上溯
                    cursor = group.getParentId();
                    depth++;
                    continue;
                }
                return buildResult(hit, mode, group.getGroupName(), chain);
            }
            cursor = group.getParentId();
            depth++;
        }
        if (depth >= MAX_GROUP_DEPTH) {
            log.warn("分组加解密继承深度超过 {} 层，提前终止: groupId={}", MAX_GROUP_DEPTH, groupId);
        }
        // 整条父链都没有非 INHERIT 的配置 ⇒ 交给调用方回退应用级
        return null;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 组装解析结果（ENABLED 透出算法/密钥；DISABLED 只透出"关闭"这一事实） */
    private EffectiveGroupEncryption buildResult(ApiGroupEncryptionConfig hit, String mode,
                                                 String groupName, List<String> chain) {
        EffectiveGroupEncryption e = new EffectiveGroupEncryption();
        e.setMode(mode);
        e.setEnabled(ApiGroupEncryptionConfig.MODE_ENABLED.equals(mode));
        e.setDisabled(ApiGroupEncryptionConfig.MODE_DISABLED.equals(mode));

        if (e.isEnabled()) {
            e.setRequestEncrypted(Boolean.TRUE.equals(hit.getRequestEncrypted()));
            e.setRequestAlgorithm(hit.getRequestAlgorithm());
            e.setRequestMode(hit.getRequestMode());
            e.setRequestKey(hit.getRequestKey());
            e.setRequestIv(hit.getRequestIv());
            e.setRequestPadding(hit.getRequestPadding());

            e.setResponseEncrypted(Boolean.TRUE.equals(hit.getResponseEncrypted()));
            e.setResponseAlgorithm(hit.getResponseAlgorithm());
            e.setResponseMode(hit.getResponseMode());
            e.setResponseKey(hit.getResponseKey());
            e.setResponseIv(hit.getResponseIv());
            e.setResponsePadding(hit.getResponsePadding());
        } else {
            // DISABLED：不透出任何密钥材料，避免前端把"已关闭"渲染成"有算法"
            e.setRequestEncrypted(false);
            e.setResponseEncrypted(false);
        }

        e.setRemark(hit.getRemark());
        e.setSourceConfigId(hit.getId());
        e.setSourceGroupId(hit.getGroupId());
        e.setSourceGroupName(groupName);
        e.setSourcePath(String.join(" / ", reverse(chain)));
        return e;
    }

    /**
     * 取某分组自己的配置行。
     *
     * <p>表上有唯一键 {@code uk_gec_group(group_id)}，正常至多一条；
     * 仍用 {@code selectList} 取首条而非 {@code selectOne} —— 历史脏数据（如唯一键建立前插入）
     * 会让 selectOne 抛 TooManyResultsException，把一次网关调用变成 500。</p>
     */
    private ApiGroupEncryptionConfig pickOwn(Long groupId) {
        List<ApiGroupEncryptionConfig> rows = groupEncMapper.selectList(
                new QueryWrapper<ApiGroupEncryptionConfig>().eq("group_id", groupId).orderByDesc("id"));
        return (rows == null || rows.isEmpty()) ? null : rows.get(0);
    }

    /**
     * 归一化 mode：空值/未知值一律按 INHERIT 处理（最保守：等价于"未配置"）。
     *
     * <p>{@code public} 而非包级私有：{@code ApiGroupEncryptionConfigServiceImpl}
     * 在 {@code com.gatekeeper.service.impl} 包，写入前校验必须与解析用同一套归一逻辑，
     * 否则「写进去的 mode / 读出来的 mode」会出现两套口径。</p>
     */
    public static String normalizeMode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return ApiGroupEncryptionConfig.MODE_INHERIT;
        }
        String v = raw.trim().toUpperCase();
        if (ApiGroupEncryptionConfig.MODE_ENABLED.equals(v) || ApiGroupEncryptionConfig.MODE_DISABLED.equals(v)) {
            return v;
        }
        return ApiGroupEncryptionConfig.MODE_INHERIT;
    }

    /** chain 是「从起点往上」的顺序，展示时应反过来（根 → 起点） */
    private static List<String> reverse(List<String> chain) {
        List<String> copy = new ArrayList<>(chain);
        Collections.reverse(copy);
        return copy;
    }
}
