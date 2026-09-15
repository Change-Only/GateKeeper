package com.gatekeeper.gateway;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiGroupEncryptionConfig;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.gateway.dto.EffectiveGroupEncryption;
import com.gatekeeper.mapper.ApiGroupEncryptionConfigMapper;
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

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * EncryptionConfigResolver 单元测试 — T15-1「分组级加解密生效配置的唯一解析口径」
 *
 * <p><b>为什么这个类的单测必须写厚</b>：它是「前端生效预览」与「网关实际加解密行为」
 * 共用的唯一解析口径。一旦它有偏差，就会出现
 * 「页面显示继承自 A 分组、网关实际按 B 加密」——这是最难排查的一类线上事故
 * （请求能进来、响应也能出去，只是密文对不上）。</p>
 *
 * <p>因此本测试把三态语义、继承链、DISABLED 的"阻断且不回退"、防环、深度上限
 * 全部钉死在断言里，任何一条被改动都会立刻红。</p>
 *
 * @author GateKeeper
 * @since T15-1 (2026-09-15)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EncryptionConfigResolver：三态语义 / 父链继承 / DISABLED 阻断 / 防环")
class EncryptionConfigResolverTest {

    @Mock
    private ApiGroupEncryptionConfigMapper groupEncMapper;
    @Mock
    private ApiGroupMapper apiGroupMapper;
    @Mock
    private ApiInterfaceMapper apiInterfaceMapper;

    private EncryptionConfigResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new EncryptionConfigResolver(groupEncMapper, apiGroupMapper, apiInterfaceMapper);
    }

    // ===================== 三态语义 =====================

    @Test
    @DisplayName("① 起点分组 ENABLED → 命中自己，enabled=true，且完整透出算法与密钥")
    void ownEnabledIsHit() {
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "核心指标"));
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(enabled(90L, 5L, "SM4", "K1")));

        EffectiveGroupEncryption e = resolver.resolveForGroup(5L);

        assertTrue(e.isEnabled(), "ENABLED 必须置 enabled=true");
        assertFalse(e.isDisabled());
        assertEquals(ApiGroupEncryptionConfig.MODE_ENABLED, e.getMode());
        assertEquals("SM4", e.getRequestAlgorithm());
        assertEquals("K1", e.getRequestKey());
        assertEquals(Long.valueOf(5L), e.getSourceGroupId(), "来源应是本分组，前端据此判「本分组维护」");
        assertEquals(Long.valueOf(90L), e.getSourceConfigId());
        assertEquals("核心指标", e.getSourcePath());
    }

    @Test
    @DisplayName("② 起点 DISABLED → disabled=true，且**不透出任何密钥材料**（防止前端把已关渲染成有算法）")
    void ownDisabledHidesKeyMaterial() {
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "核心指标"));
        ApiGroupEncryptionConfig row = disabled(91L, 5L);
        // 脏数据防线：即使库里有残留密钥，DISABLED 也不得透出
        row.setRequestAlgorithm("SM4");
        row.setRequestKey("LEAK");
        row.setResponseKey("LEAK2");
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row));

        EffectiveGroupEncryption e = resolver.resolveForGroup(5L);

        assertTrue(e.isDisabled(), "DISABLED 必须置 disabled=true");
        assertFalse(e.isEnabled());
        assertNull(e.getRequestKey(), "DISABLED 绝不透出密钥 —— 这是防止泄密的关键断言");
        assertNull(e.getResponseKey());
        assertNull(e.getRequestAlgorithm());
        assertEquals(Boolean.FALSE, e.getRequestEncrypted());
        assertEquals(Boolean.FALSE, e.getResponseEncrypted());
    }

    @Test
    @DisplayName("③ INHERIT 等价于「本分组未配置」→ 继续沿 parent_id 上溯，不终止")
    void inheritKeepsClimbing() {
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "核心指标"));
        when(apiGroupMapper.selectById(3L)).thenReturn(group(3L, null, "配网"));
        // 5 有 INHERIT 行，3 有 ENABLED 行
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(inherit(92L, 5L)),
                        Collections.singletonList(enabled(93L, 3L, "AES", "K2")));

        EffectiveGroupEncryption e = resolver.resolveForGroup(5L);

        assertTrue(e.isEnabled());
        assertEquals(Long.valueOf(3L), e.getSourceGroupId(), "INHERIT 行不得被当成命中，必须继续上溯");
        assertEquals(Boolean.TRUE, e.getRequestEncrypted());
        assertEquals("AES", e.getRequestAlgorithm());
        assertEquals("配网 / 核心指标", e.getSourcePath(), "链路按「根 → 起点」展示");
    }

    @Test
    @DisplayName("④ 起点无配置行 → 直接上溯到父级命中，路径只含走过的分组")
    void climbsWhenNoRowAtStart() {
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "核心指标"));
        when(apiGroupMapper.selectById(3L)).thenReturn(group(3L, null, "配网"));
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.emptyList(),
                        Collections.singletonList(enabled(93L, 3L, "SM4", "K3")));

        EffectiveGroupEncryption e = resolver.resolveForGroup(5L);

        assertEquals(Long.valueOf(3L), e.getSourceGroupId());
        assertEquals("配网 / 核心指标", e.getSourcePath());
    }

    @Test
    @DisplayName("⑤ DISABLED 优先于更上层的 ENABLED —— 近层显式关闭即阻断，不得被祖先的启用覆盖")
    void disabledBlocksAncestorEnabled() {
        // 5(parent=3) DISABLED，3(parent=1) ENABLED，1 ENABLED
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "核心指标"));
        when(apiGroupMapper.selectById(3L)).thenReturn(group(3L, 1L, "配网"));
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(disabled(91L, 5L)));

        EffectiveGroupEncryption e = resolver.resolveForGroup(5L);

        assertTrue(e.isDisabled(), "最近一层的 DISABLED 必须终止上溯");
        assertEquals(Long.valueOf(5L), e.getSourceGroupId());
        assertNull(e.getRequestKey());
    }

    @Test
    @DisplayName("⑥ DISABLED 的语义是「这条链路不需要加解密」→ 返回 disabled 而非 null，调用方据此**不回退应用级**")
    void disabledIsNotifiedAsNonNull() {
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, null, "独立分组"));
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(disabled(91L, 5L)));

        EffectiveGroupEncryption e = resolver.resolveForGroup(5L);

        // 若这里返回 null，网关就会去用应用级配置 = 用户明确说了不要加密却被加密，是最严重的语义错
        assertNull(e == null ? null : e.getRequestKey());
        assertTrue(e != null && e.isDisabled(), "绝不能返回 null —— null 代表「回退应用级」");
    }

    @Test
    @DisplayName("⑦ 整条父链都没有非 INHERIT 配置 → 返回 null（调用方继续回退应用级）")
    void noConfigAnywhereReturnsNull() {
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "核心指标"));
        when(apiGroupMapper.selectById(3L)).thenReturn(group(3L, null, "配网"));
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(inherit(92L, 5L)),
                        Collections.emptyList());

        assertNull(resolver.resolveForGroup(5L),
                "null 是「回退应用级」的信号，与 DISABLED 的「不回退」必须区分");
    }

    // ===================== 防御 =====================

    @Test
    @DisplayName("防环：分组树成环时提前终止并返回 null，绝不无限循环")
    void cycleStopsGracefully() {
        // 5 → 3 → 5 成环，且都没有配置
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "A"));
        when(apiGroupMapper.selectById(3L)).thenReturn(group(3L, 5L, "B"));
        when(groupEncMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        assertNull(resolver.resolveForGroup(5L), "成环应停下并当「无配置」处理，而不是死循环");
    }

    @Test
    @DisplayName("groupId 为 null / 分组不存在 → 返回 null，不抛异常")
    void nullInputsAreSafe() {
        assertNull(resolver.resolveForGroup(null));
        when(apiGroupMapper.selectById(404L)).thenReturn(null);
        assertNull(resolver.resolveForGroup(404L));
    }

    @Test
    @DisplayName("父链上有分组被删（selectById 返回 null）→ 就地终止，不抛 NPE")
    void danglingParentStops() {
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "孤儿链"));
        when(apiGroupMapper.selectById(3L)).thenReturn(null);
        when(groupEncMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        assertNull(resolver.resolveForGroup(5L));
    }

    @Test
    @DisplayName("多层 INHERIT 后命中祖先，链路完整（含中间 INHERIT 层）")
    void deepChainIncludesInheritLayers() {
        when(apiGroupMapper.selectById(7L)).thenReturn(group(7L, 5L, "L3"));
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, 3L, "L2"));
        when(apiGroupMapper.selectById(3L)).thenReturn(group(3L, null, "L1"));
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(inherit(1L, 7L)),
                        Collections.singletonList(inherit(2L, 5L)),
                        Collections.singletonList(enabled(3L, 3L, "SM4", "K")));

        EffectiveGroupEncryption e = resolver.resolveForGroup(7L);

        assertEquals(Long.valueOf(3L), e.getSourceGroupId());
        assertEquals("L1 / L2 / L3", e.getSourcePath());
    }

    @Test
    @DisplayName("分组树深度超过上限时提前终止（兜底脏数据，不无限循环）")
    void exceedsMaxDepthStops() {
        // 造一条比 MAX_GROUP_DEPTH 更长的链：i 的父是 i+1
        for (long i = 1; i <= EncryptionConfigResolver.MAX_GROUP_DEPTH + 5; i++) {
            when(apiGroupMapper.selectById(i)).thenReturn(group(i, i + 1, "G" + i));
        }
        when(groupEncMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        assertNull(resolver.resolveForGroup(1L), "超深度应停下，不得无限循环");
    }

    // ===================== resolveByInterface =====================

    @Test
    @DisplayName("resolveByInterface：先由 api_interface.group_id 定位分组，再走同一条父链")
    void resolveByInterfaceDelegatesToGroupChain() {
        when(apiInterfaceMapper.selectById(20L)).thenReturn(api(20L, 5L));
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, null, "核心指标"));
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(enabled(90L, 5L, "SM4", "K1")));

        EffectiveGroupEncryption e = resolver.resolveByInterface(20L);

        assertTrue(e.isEnabled());
        assertEquals(Long.valueOf(5L), e.getSourceGroupId());
    }

    @Test
    @DisplayName("resolveByInterface：接口不存在 / 接口未归入分组 → null，不抛异常")
    void resolveByInterfaceSafeOnMissing() {
        assertNull(resolver.resolveByInterface(null));
        when(apiInterfaceMapper.selectById(404L)).thenReturn(null);
        assertNull(resolver.resolveByInterface(404L));

        when(apiInterfaceMapper.selectById(21L)).thenReturn(api(21L, null));
        assertNull(resolver.resolveByInterface(21L), "groupId 为 null 时不得去查分组链");
    }

    // ===================== normalizeMode =====================

    @Test
    @DisplayName("normalizeMode：空 / null / 未知值一律归为 INHERIT（最保守＝等价于未配置）")
    void normalizeModeIsConservative() {
        assertEquals(ApiGroupEncryptionConfig.MODE_INHERIT, EncryptionConfigResolver.normalizeMode(null));
        assertEquals(ApiGroupEncryptionConfig.MODE_INHERIT, EncryptionConfigResolver.normalizeMode(""));
        assertEquals(ApiGroupEncryptionConfig.MODE_INHERIT, EncryptionConfigResolver.normalizeMode("   "));
        assertEquals(ApiGroupEncryptionConfig.MODE_INHERIT, EncryptionConfigResolver.normalizeMode("WHATEVER"));
        assertEquals(ApiGroupEncryptionConfig.MODE_INHERIT, EncryptionConfigResolver.normalizeMode("true"));
    }

    @Test
    @DisplayName("normalizeMode：大小写不敏感、去首尾空白")
    void normalizeModeIsCaseInsensitive() {
        assertEquals(ApiGroupEncryptionConfig.MODE_ENABLED, EncryptionConfigResolver.normalizeMode("enabled"));
        assertEquals(ApiGroupEncryptionConfig.MODE_ENABLED, EncryptionConfigResolver.normalizeMode(" ENABLED "));
        assertEquals(ApiGroupEncryptionConfig.MODE_DISABLED, EncryptionConfigResolver.normalizeMode("disabled"));
    }

    // ===================== 脏数据：同一分组多行 =====================

    @Test
    @DisplayName("同一分组存在多行（唯一键建立前的脏数据）时取 id 最大的一条，不抛 TooManyResults")
    void duplicateRowsPicksLatest() {
        when(apiGroupMapper.selectById(5L)).thenReturn(group(5L, null, "脏数据分组"));
        ApiGroupEncryptionConfig older = enabled(10L, 5L, "SM4", "OLD");
        ApiGroupEncryptionConfig newer = enabled(99L, 5L, "AES", "NEW");
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(newer.getId() > older.getId()
                        ? java.util.Arrays.asList(newer, older)
                        : java.util.Arrays.asList(older, newer));

        EffectiveGroupEncryption e = resolver.resolveForGroup(5L);

        assertEquals(Long.valueOf(99L), e.getSourceConfigId(), "应取 mapper 排在最前（id 最大）的那条");
        assertEquals("NEW", e.getRequestKey());
    }

    // ===================== fixtures =====================

    private ApiGroup group(Long id, Long parentId, String name) {
        ApiGroup g = new ApiGroup();
        g.setId(id);
        g.setParentId(parentId);
        g.setGroupName(name);
        return g;
    }

    private ApiInterface api(Long id, Long groupId) {
        ApiInterface a = new ApiInterface();
        a.setId(id);
        a.setGroupId(groupId);
        return a;
    }

    private ApiGroupEncryptionConfig enabled(Long id, Long groupId, String algorithm, String key) {
        ApiGroupEncryptionConfig c = new ApiGroupEncryptionConfig();
        c.setId(id);
        c.setGroupId(groupId);
        c.setMode(ApiGroupEncryptionConfig.MODE_ENABLED);
        c.setRequestEncrypted(true);
        c.setRequestAlgorithm(algorithm);
        c.setRequestMode("ECB");
        c.setRequestKey(key);
        c.setRequestPadding("PKCS5Padding");
        c.setResponseEncrypted(true);
        c.setResponseAlgorithm(algorithm);
        c.setResponseMode("ECB");
        c.setResponseKey(key + "-resp");
        c.setResponsePadding("PKCS5Padding");
        return c;
    }

    private ApiGroupEncryptionConfig disabled(Long id, Long groupId) {
        ApiGroupEncryptionConfig c = new ApiGroupEncryptionConfig();
        c.setId(id);
        c.setGroupId(groupId);
        c.setMode(ApiGroupEncryptionConfig.MODE_DISABLED);
        c.setRequestEncrypted(false);
        c.setResponseEncrypted(false);
        return c;
    }

    private ApiGroupEncryptionConfig inherit(Long id, Long groupId) {
        ApiGroupEncryptionConfig c = new ApiGroupEncryptionConfig();
        c.setId(id);
        c.setGroupId(groupId);
        c.setMode(ApiGroupEncryptionConfig.MODE_INHERIT);
        c.setRequestEncrypted(false);
        c.setResponseEncrypted(false);
        return c;
    }
}
