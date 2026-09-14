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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * EnvConfigResolver 单元测试 — T13「生效环境配置的唯一解析口径」
 *
 * <p>这个类是三处共用的（网关转发 / 接口测试直连 / 前端只读预览），
 * 一旦它有偏差就会出现"页面显示继承自 A 分组、网关实际走 B"这种最难查的错位，
 * 所以把三条优先级、继承链、防环、URL 拼接都钉死在单测里。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EnvConfigResolver：接口级优先 / 分组继承 / 防环 / 兜底 / joinUrl")
class EnvConfigResolverTest {

    @Mock
    private ApiEnvConfigMapper apiEnvConfigMapper;
    @Mock
    private ApiGroupEnvConfigMapper groupEnvConfigMapper;
    @Mock
    private ApiGroupMapper apiGroupMapper;
    @Mock
    private ApiInterfaceMapper apiInterfaceMapper;

    private EnvConfigResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new EnvConfigResolver(apiEnvConfigMapper, groupEnvConfigMapper, apiGroupMapper, apiInterfaceMapper);
    }

    // ===================== 优先级 =====================

    @Test
    @DisplayName("① 接口级覆盖优先于分组继承（老表 api_env_config 存在即命中，不再看分组）")
    void interfaceOverrideWinsOverGroup() {
        when(apiEnvConfigMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(envCfg(77L, "prod", "http://iface:8080", 1)));

        ApiInterface api = api(20L, 5L, "/test");
        EffectiveEnvConfig e = resolver.resolve(api, "prod");

        assertEquals(EffectiveEnvConfig.SOURCE_INTERFACE, e.getSourceType());
        assertEquals("http://iface:8080", e.getUpstreamUrl());
        assertEquals(Long.valueOf(77L), e.getSourceConfigId());
        assertEquals(Integer.valueOf(1), e.getMockEnabled());
    }

    @Test
    @DisplayName("①-补 version 为空的行优先于 version 非空的行（同 (api_id, env) 多条时的挑法）")
    void interfaceOverridePrefersNullVersion() {
        ApiEnvConfig v2 = envCfg(101L, "prod", "http://v2", 0);
        v2.setVersion("v2");
        ApiEnvConfig generic = envCfg(100L, "prod", "http://generic", 0);
        // mapper 已 orderByDesc("id") ⇒ 先返回 id 大的
        when(apiEnvConfigMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(v2, generic)));

        EffectiveEnvConfig e = resolver.resolve(api(20L, 5L, "/test"), "prod");

        assertEquals(Long.valueOf(100L), e.getSourceConfigId(), "应挑 version IS NULL 的那条，而不是 id 最大的");
        assertEquals("http://generic", e.getUpstreamUrl());
    }

    @Test
    @DisplayName("② 无接口级配置时沿分组树向上继承，且整条配置一起继承（含超时/重试/Mock）")
    void fallsBackToGroupChain() {
        when(apiEnvConfigMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        // 分组 5（核心指标，parent=3）没配 → 分组 3（配网，parent=1）配了
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "核心指标"));
        when(apiGroupMapper.selectById(3L)).thenReturn(group(3L, 1L, "配网"));
        when(groupEnvConfigMapper.selectOne(any(QueryWrapper.class)))
                .thenReturn(null, groupCfg(9L, 3L, "prod", "http://upi:8080", 1234, 2345, 2, 1));

        EffectiveEnvConfig e = resolver.resolve(api(20L, 5L, "/test"), "prod");

        assertEquals(EffectiveEnvConfig.SOURCE_GROUP, e.getSourceType());
        assertEquals("http://upi:8080", e.getUpstreamUrl());
        assertEquals(Integer.valueOf(1234), e.getConnectTimeout());
        assertEquals(Integer.valueOf(2345), e.getReadTimeout());
        assertEquals(Integer.valueOf(2), e.getRetryCount());
        assertEquals(Integer.valueOf(1), e.getMockEnabled());
        assertEquals(Long.valueOf(3L), e.getSourceGroupId());
        assertEquals("配网 / 核心指标", e.getSourcePath(), "来源链路应按「根 → 起点」展示");
    }

    @Test
    @DisplayName("②-负 起点分组自己就配了 → 不再向上找")
    void groupChainStopsAtNearest() {
        when(apiEnvConfigMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "核心指标"));
        when(groupEnvConfigMapper.selectOne(any(QueryWrapper.class)))
                .thenReturn(groupCfg(11L, 5L, "prod", "http://own:8080", 1000, 2000, 0, 0));

        EffectiveEnvConfig e = resolver.resolve(api(20L, 5L, "/test"), "prod");

        assertEquals(Long.valueOf(5L), e.getSourceGroupId());
        assertEquals("http://own:8080", e.getUpstreamUrl());
    }

    @Test
    @DisplayName("③ 都没有配置时返回 DEFAULT 兜底（由调用方回退接口 backend_url）")
    void noConfigFallsBackToDefault() {
        when(apiEnvConfigMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        when(apiGroupMapper.selectById(anyLong())).thenReturn(null);

        EffectiveEnvConfig e = resolver.resolve(api(20L, 5L, "/test"), "dev");

        assertEquals(EffectiveEnvConfig.SOURCE_DEFAULT, e.getSourceType());
        assertNull(e.getUpstreamUrl());
        assertEquals("dev", e.getEnvCode());
    }

    // ===================== 防御 =====================

    @Test
    @DisplayName("分组树成环时提前终止，不无限循环（脏数据防线）")
    void cycleStopsGracefully() {
        when(apiEnvConfigMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        // 5 → 3 → 5 成环
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "A"));
        when(apiGroupMapper.selectById(3L)).thenReturn(group(3L, 5L, "B"));
        when(groupEnvConfigMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        EffectiveEnvConfig e = resolver.resolve(api(20L, 5L, "/test"), "prod");

        assertEquals(EffectiveEnvConfig.SOURCE_DEFAULT, e.getSourceType(), "成环时应停下并兜底，而不是死循环");
    }

    @Test
    @DisplayName("api 为 null / apiId 为 null / 环境为空 都不得抛异常")
    void nullInputsAreSafe() {
        assertNotNull(resolver.resolve((ApiInterface) null, "prod"));
        assertEquals(EffectiveEnvConfig.SOURCE_DEFAULT, resolver.resolve((ApiInterface) null, "prod").getSourceType());
        assertEquals(EnvConfigResolver.DEFAULT_ENV, resolver.resolve((ApiInterface) null, null).getEnvCode());

        when(apiInterfaceMapper.selectById(null)).thenReturn(null);
        assertEquals(EffectiveEnvConfig.SOURCE_DEFAULT, resolver.resolve((Long) null, "prod").getSourceType());
    }

    @Test
    @DisplayName("接口未归入分组（groupId=null）时直接兜底，不查分组链")
    void nullGroupIdFallsBack() {
        when(apiEnvConfigMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        EffectiveEnvConfig e = resolver.resolve(api(20L, null, "/test"), "prod");

        assertEquals(EffectiveEnvConfig.SOURCE_DEFAULT, e.getSourceType());
    }

    // ===================== resolveForGroup =====================

    @Test
    @DisplayName("resolveForGroup 只看分组链，不掺杂接口级覆盖")
    void resolveForGroupIgnoresInterfaceOverride() {
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, null, "核心指标"));
        when(groupEnvConfigMapper.selectOne(any(QueryWrapper.class)))
                .thenReturn(groupCfg(9L, 5L, "test", "http://g:8080", 1000, 3000, 0, 1));

        EffectiveEnvConfig e = resolver.resolveForGroup(5L, "test");

        assertEquals(EffectiveEnvConfig.SOURCE_GROUP, e.getSourceType());
        assertEquals("http://g:8080", e.getUpstreamUrl());
    }

    @Test
    @DisplayName("resolveForGroup 未命中返回 DEFAULT，且 mockEnabled 默认不是开启")
    void resolveForGroupDefault() {
        when(apiGroupMapper.selectById(anyLong())).thenReturn(null);

        EffectiveEnvConfig e = resolver.resolveForGroup(99L, "prod");

        assertEquals(EffectiveEnvConfig.SOURCE_DEFAULT, e.getSourceType());
        assertTrue(!e.isMockOn(), "默认绝不能是 Mock 开启");
    }

    // ===================== joinUrl =====================

    @Test
    @DisplayName("joinUrl：前缀去尾斜杠、URI 补头斜杠，避免 //test")
    void joinUrlNormalizes() {
        assertEquals("http://svc:8080/test", EnvConfigResolver.joinUrl("http://svc:8080/", "/test"));
        assertEquals("http://svc:8080/test", EnvConfigResolver.joinUrl("http://svc:8080", "test"));
        assertEquals("http://svc:8080", EnvConfigResolver.joinUrl("http://svc:8080", null));
        assertEquals("http://svc:8080", EnvConfigResolver.joinUrl("http://svc:8080", ""));
        assertNull(EnvConfigResolver.joinUrl(null, "/test"));
        assertNull(EnvConfigResolver.joinUrl("  ", "/test"));
    }

    // ===================== fixtures =====================

    private ApiInterface api(Long id, Long groupId, String path) {
        ApiInterface a = new ApiInterface();
        a.setId(id);
        a.setGroupId(groupId);
        a.setInterfacePath(path);
        a.setRequestMethod("GET");
        return a;
    }

    private ApiGroup group(Long id, Long parentId, String name) {
        ApiGroup g = new ApiGroup();
        g.setId(id);
        g.setParentId(parentId);
        g.setGroupName(name);
        return g;
    }

    private ApiEnvConfig envCfg(Long id, String env, String url, int mock) {
        ApiEnvConfig c = new ApiEnvConfig();
        c.setId(id);
        c.setEnvCode(env);
        c.setUpstreamUrl(url);
        c.setMockEnabled(mock);
        c.setConfigStatus(1);
        return c;
    }

    private ApiGroupEnvConfig groupCfg(Long id, Long groupId, String env, String url,
                                       int connect, int read, int retry, int mock) {
        ApiGroupEnvConfig c = new ApiGroupEnvConfig();
        c.setId(id);
        c.setGroupId(groupId);
        c.setEnvCode(env);
        c.setUpstreamUrl(url);
        c.setConnectTimeout(connect);
        c.setReadTimeout(read);
        c.setRetryCount(retry);
        c.setMockEnabled(mock);
        c.setMockStatus(200);
        c.setConfigStatus(1);
        return c;
    }
}
