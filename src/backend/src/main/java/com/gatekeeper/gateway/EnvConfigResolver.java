package com.gatekeeper.gateway;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiEnvConfig;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiGroupEnvConfig;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.gateway.dto.EffectiveEnvConfig;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import com.gatekeeper.mapper.ApiGroupEnvConfigMapper;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 生效环境配置解析器 — T13「环境配置下沉到分组 + 分组树向上继承」的唯一解析口径
 *
 * <p><b>为什么要收敛成一个组件</b>：环境配置有三处要用的地方 —— 网关转发（用哪个上游/超时/重试/Mock）、
 * 接口测试（直连时拼哪个地址）、前端只读预览（显示什么、来自哪里）。如果各写一份父链遍历，
 * 迟早出现"页面显示继承自 A 分组、网关实际走 B"这种最难查的错位。所以三处全部走本类。</p>
 *
 * <p><b>解析优先级</b>（高 → 低）：
 * <ol>
 *   <li>接口级覆盖：{@code api_env_config} 里 (api_id, env_code) 的行（老表，一行未动，纯兼容）。
 *       同一 (api_id, env_code) 可能有多条（version 可空导致的历史脏数据）⇒ 取 version IS NULL 优先、
 *       否则 id 最大的一条。</li>
 *   <li>分组继承：从 {@code api_interface.group_id} 起**沿 parent_id 向上**找最近一条
 *       (group_id, env_code) 的分组配置；**整体继承**（前缀/超时/重试/Mock 一起来），不做逐字段合并。</li>
 *   <li>兜底：返回 {@link EffectiveEnvConfig#SOURCE_DEFAULT}，由调用方回退
 *       {@code api_interface.backend_url} 与接口自身 timeoutMs。</li>
 * </ol></p>
 *
 * <p><b>防御</b>：分组树理论上可能成环（人工改库、脏数据），父链遍历带 visited 集合与最大深度，
 * 成环时立即停止并 WARN，绝不无限循环。</p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnvConfigResolver {

    /** 环境配置父链最大遍历深度（正常分组树远小于此值，仅用于兜住环） */
    static final int MAX_GROUP_DEPTH = 32;

    /** 未显式指定环境时的默认环境（与 PermissionHandler / AppAuthHandler 口径一致） */
    public static final String DEFAULT_ENV = "prod";

    private final ApiEnvConfigMapper apiEnvConfigMapper;
    private final ApiGroupEnvConfigMapper groupEnvConfigMapper;
    private final ApiGroupMapper apiGroupMapper;
    private final ApiInterfaceMapper apiInterfaceMapper;

    /**
     * 按接口 ID 解析生效配置。
     *
     * @param apiId   接口 ID
     * @param envCode 环境编码（空则取默认环境 prod）
     * @return 生效配置（永不返回 null）
     */
    public EffectiveEnvConfig resolve(Long apiId, String envCode) {
        String env = normalizeEnv(envCode);
        if (apiId == null) {
            return EffectiveEnvConfig.fallback(env);
        }
        ApiInterface api = apiInterfaceMapper.selectById(apiId);
        return resolve(api, env);
    }

    /**
     * 按接口实体解析生效配置（网关侧已有实体，避免重复查库）。
     *
     * @param api     接口实体，可为 null
     * @param envCode 环境编码
     * @return 生效配置（永不返回 null）
     */
    public EffectiveEnvConfig resolve(ApiInterface api, String envCode) {
        String env = normalizeEnv(envCode);
        if (api == null) {
            return EffectiveEnvConfig.fallback(env);
        }
        // ① 接口级覆盖（老表 api_env_config）
        EffectiveEnvConfig byInterface = fromInterface(api.getId(), env);
        if (byInterface != null) {
            return byInterface;
        }
        // ② 分组继承（新表 api_group_env_config，沿 parent_id 向上）
        EffectiveEnvConfig byGroup = fromGroupChain(api.getGroupId(), env);
        if (byGroup != null) {
            return byGroup;
        }
        // ③ 兜底
        return EffectiveEnvConfig.fallback(env);
    }

    /**
     * 解析某个分组的生效配置（**不包含**该分组下接口的接口级覆盖）。
     *
     * <p>供「分组环境配置」页面展示"我这个分组在某环境下的生效配置（可能继承自父级）"。</p>
     *
     * @param groupId 分组 ID
     * @param envCode 环境编码
     * @return 生效配置（永不返回 null）
     */
    public EffectiveEnvConfig resolveForGroup(Long groupId, String envCode) {
        String env = normalizeEnv(envCode);
        EffectiveEnvConfig byGroup = fromGroupChain(groupId, env);
        return byGroup != null ? byGroup : EffectiveEnvConfig.fallback(env);
    }

    /**
     * 拼接完整后端地址：服务前缀 + 接口 URI。
     *
     * <p>前缀去尾 `/`、URI 补头 `/`，避免出现 {@code http://svc:8080//test}。
     * 已有实现散落在前端（ApiEnvConfigTab.fullUrl）与本类，口径必须一致。</p>
     *
     * @param prefix 服务前缀，如 {@code http://order-svc.dev:8080}
     * @param uri    接口 URI，如 {@code /test}
     * @return 完整地址；prefix 为空时返回 null
     */
    public static String joinUrl(String prefix, String uri) {
        if (!StringUtils.hasText(prefix)) {
            return null;
        }
        String base = prefix.trim().replaceAll("/+$", "");
        if (!StringUtils.hasText(uri)) {
            return base;
        }
        String path = uri.trim();
        return base + (path.startsWith("/") ? path : "/" + path);
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 归一化环境编码，空值取默认环境 */
    private static String normalizeEnv(String envCode) {
        return StringUtils.hasText(envCode) ? envCode.trim() : DEFAULT_ENV;
    }

    /**
     * 接口级覆盖查询。
     *
     * <p>同一 (api_id, env_code) 可能有多条（version 可空 ⇒ 唯一键对 NULL 不生效），
     * 这里按「version IS NULL 优先 → id 最大」挑一条，**不用 selectOne**（会抛 TooManyResultsException）。</p>
     */
    private EffectiveEnvConfig fromInterface(Long apiId, String env) {
        if (apiId == null) {
            return null;
        }
        List<ApiEnvConfig> rows = apiEnvConfigMapper.selectList(new QueryWrapper<ApiEnvConfig>()
                .eq("api_id", apiId)
                .eq("env_code", env)
                .orderByDesc("id"));
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        ApiEnvConfig picked = null;
        for (ApiEnvConfig r : rows) {
            if (r.getVersion() == null) {
                picked = r;
                break;
            }
        }
        if (picked == null) {
            picked = rows.get(0);
        }
        EffectiveEnvConfig e = new EffectiveEnvConfig();
        e.setEnvCode(env);
        e.setUpstreamUrl(picked.getUpstreamUrl());
        e.setConnectTimeout(picked.getConnectTimeout());
        e.setReadTimeout(picked.getReadTimeout());
        e.setRetryCount(picked.getRetryCount());
        e.setMockEnabled(picked.getMockEnabled());
        // 老表没有 mock_status / mock_response 列 ⇒ 走默认（200 / 默认提示体）
        e.setConfigStatus(picked.getConfigStatus());
        e.setSourceType(EffectiveEnvConfig.SOURCE_INTERFACE);
        e.setSourceConfigId(picked.getId());
        return e;
    }

    /**
     * 分组继承查询：从 groupId 起沿 parent_id 向上，返回**最近一条**该环境的配置。
     *
     * @param groupId 起始分组 ID
     * @param env     环境编码
     * @return 命中则返回（sourceType=GROUP），未命中返回 null
     */
    private EffectiveEnvConfig fromGroupChain(Long groupId, String env) {
        if (groupId == null) {
            return null;
        }
        Set<Long> visited = new HashSet<>();
        List<String> chain = new ArrayList<>();
        Long cursor = groupId;
        int depth = 0;
        while (cursor != null && depth < MAX_GROUP_DEPTH) {
            if (!visited.add(cursor)) {
                log.warn("分组树成环，继承解析提前终止: groupId={}, env={}, chain={}", groupId, env, chain);
                break;
            }
            ApiGroup group = apiGroupMapper.selectById(cursor);
            if (group == null) {
                break;
            }
            chain.add(group.getGroupName());
            ApiGroupEnvConfig hit = groupEnvConfigMapper.selectOne(new QueryWrapper<ApiGroupEnvConfig>()
                    .eq("group_id", cursor)
                    .eq("env_code", env));
            if (hit != null) {
                EffectiveEnvConfig e = new EffectiveEnvConfig();
                e.setEnvCode(env);
                e.setUpstreamUrl(hit.getUpstreamUrl());
                e.setConnectTimeout(hit.getConnectTimeout());
                e.setReadTimeout(hit.getReadTimeout());
                e.setRetryCount(hit.getRetryCount());
                e.setMockEnabled(hit.getMockEnabled());
                e.setMockStatus(hit.getMockStatus());
                e.setMockResponse(hit.getMockResponse());
                e.setConfigStatus(hit.getConfigStatus());
                e.setSourceType(EffectiveEnvConfig.SOURCE_GROUP);
                e.setSourceConfigId(hit.getId());
                e.setSourceGroupId(hit.getGroupId());
                e.setSourceGroupName(group.getGroupName());
                e.setSourcePath(String.join(" / ", reverse(chain)));
                return e;
            }
            cursor = group.getParentId();
            depth++;
        }
        if (depth >= MAX_GROUP_DEPTH) {
            log.warn("分组继承深度超过 {} 层，提前终止: groupId={}", MAX_GROUP_DEPTH, groupId);
        }
        return null;
    }

    /** chain 是「从起点往上」的顺序，展示时应反过来（根 → 起点） */
    private static List<String> reverse(List<String> chain) {
        List<String> copy = new ArrayList<>(chain);
        Collections.reverse(copy);
        return copy;
    }
}
