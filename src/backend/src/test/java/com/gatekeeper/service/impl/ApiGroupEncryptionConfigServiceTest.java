package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.ApiGroupEncryptionConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.EncryptionConfigResolver;
import com.gatekeeper.gateway.dto.EffectiveGroupEncryption;
import com.gatekeeper.mapper.ApiGroupEncryptionConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiGroupEncryptionConfigServiceImpl 单元测试 — T15-1
 *
 * <p><b>本测试存在的核心理由是"全量覆盖"这条写入语义</b>。
 * MyBatis-Plus 的 {@code updateById} 走 NOT_NULL 策略（null 字段不写库），
 * 而本域最常见的操作恰恰是「把 ENABLED 改成 DISABLED / INHERIT」——
 * 需要把算法、密钥一起清成 null。若用 updateById，就会出现
 * 「界面上已显示"不需要加解密"，库里却还留着上一版密钥」的幽灵数据：
 * 一旦有人把 mode 改回来，旧密钥会莫名其妙地"复活"。</p>
 *
 * <p>故实现对写路径做了两件事，测试逐条钉死：
 * ① 先删后插（保证 null 真的落库）；
 * ② 写前按 mode 归一载荷（DISABLED/INHERIT 强制清空密钥材料），
 *    ENABLED 则要求"开启了的一侧必须有算法与密钥"——
 *    拦住"配了加密却没有密钥"这种要到线上才炸的配置。</p>
 *
 * @author GateKeeper
 * @since T15-1 (2026-09-15)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiGroupEncryptionConfigService：全量覆盖 / 三态校验 / 归一")
class ApiGroupEncryptionConfigServiceTest {

    @Mock
    private ApiGroupEncryptionConfigMapper groupEncMapper;
    @Mock
    private EncryptionConfigResolver resolver;

    private ApiGroupEncryptionConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ApiGroupEncryptionConfigServiceImpl(groupEncMapper, resolver);
    }

    // ===================== 查询委托 =====================

    @Test
    @DisplayName("getEffective 直接委托解析器（保证前端预览与网关同一份口径）")
    void getEffectiveDelegatesToResolver() {
        EffectiveGroupEncryption stub = new EffectiveGroupEncryption();
        stub.setMode(ApiGroupEncryptionConfig.MODE_ENABLED);
        when(resolver.resolveForGroup(5L)).thenReturn(stub);

        assertSame(stub, service.getEffective(5L), "必须原样透传解析结果，不得二次加工");
    }

    @Test
    @DisplayName("getOwn：groupId 为 null 直接返回 null；有多行时取 id 最大的一条，不抛 TooManyResults")
    void getOwnPicksLatestRow() {
        assertNull(service.getOwn(null));

        ApiGroupEncryptionConfig a = enabled(10L, 5L);
        ApiGroupEncryptionConfig b = enabled(99L, 5L);
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(java.util.Arrays.asList(b, a));

        assertEquals(Long.valueOf(99L), service.getOwn(5L).getId());
    }

    @Test
    @DisplayName("getOwn：无行返回 null")
    void getOwnReturnsNullWhenAbsent() {
        when(groupEncMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        assertNull(service.getOwn(5L));
    }

    // ===================== upsert：校验 =====================

    @Test
    @DisplayName("upsert：groupId 为 null → 400，且不落库")
    void upsertRejectsNullGroupId() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.upsert(null, enabled(1L, 5L)));
        assertEquals(400, ex.getCode());
        verify(groupEncMapper, never()).insert(any());
    }

    @Test
    @DisplayName("upsert：config 为 null → 400")
    void upsertRejectsNullConfig() {
        GatewayException ex = assertThrows(GatewayException.class, () -> service.upsert(5L, null));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("upsert：ENABLED 但入参与返参都没开启 → 400（提示改选「不需要加解密」）")
    void upsertRejectsEnabledWithNothingOn() {
        ApiGroupEncryptionConfig c = new ApiGroupEncryptionConfig();
        c.setMode(ApiGroupEncryptionConfig.MODE_ENABLED);
        c.setRequestEncrypted(false);
        c.setResponseEncrypted(false);

        GatewayException ex = assertThrows(GatewayException.class, () -> service.upsert(5L, c));
        assertEquals(400, ex.getCode());
        verify(groupEncMapper, never()).insert(any());
    }

    @Test
    @DisplayName("upsert：ENABLED 开启入参但没填算法 → 400（拦住「配了加密却没密钥」，这是线上才炸的配置）")
    void upsertRejectsEnabledWithoutAlgorithm() {
        ApiGroupEncryptionConfig c = new ApiGroupEncryptionConfig();
        c.setMode(ApiGroupEncryptionConfig.MODE_ENABLED);
        c.setRequestEncrypted(true);
        c.setRequestKey("K");           // 有密钥
        c.setRequestAlgorithm(null);    // 缺算法

        GatewayException ex = assertThrows(GatewayException.class, () -> service.upsert(5L, c));
        assertEquals(400, ex.getCode());
        verify(groupEncMapper, never()).insert(any());
    }

    @Test
    @DisplayName("upsert：ENABLED 开启返参但密钥为空白串 → 400（空白不算填了）")
    void upsertRejectsEnabledWithBlankKey() {
        ApiGroupEncryptionConfig c = new ApiGroupEncryptionConfig();
        c.setMode(ApiGroupEncryptionConfig.MODE_ENABLED);
        c.setResponseEncrypted(true);
        c.setResponseAlgorithm("SM4");
        c.setResponseKey("   ");

        assertEquals(400, assertThrows(GatewayException.class, () -> service.upsert(5L, c)).getCode());
    }

    // ===================== upsert：全量覆盖 =====================

    @Test
    @DisplayName("upsert：ENABLED 合法 → 先删已有行再插入，mode 归一为大写")
    void upsertEnabledReplacesExistingRow() {
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(enabled(90L, 5L)));
        when(groupEncMapper.deleteById(90L)).thenReturn(1);

        ApiGroupEncryptionConfig in = new ApiGroupEncryptionConfig();
        in.setMode("enabled");          // 小写，需被归一
        in.setRequestEncrypted(true);
        in.setRequestAlgorithm("SM4");
        in.setRequestKey("K1");

        service.upsert(5L, in);

        verify(groupEncMapper).deleteById(90L);   // 先删
        ArgumentCaptor<ApiGroupEncryptionConfig> cap =
                ArgumentCaptor.forClass(ApiGroupEncryptionConfig.class);
        verify(groupEncMapper).insert(cap.capture());   // 后插
        ApiGroupEncryptionConfig saved = cap.getValue();
        assertEquals(ApiGroupEncryptionConfig.MODE_ENABLED, saved.getMode(), "mode 必须归一为大写");
        assertEquals(Long.valueOf(5L), saved.getGroupId(), "groupId 以方法参数为准，不信任请求体");
        assertNull(saved.getId(), "插入前必须清空 id，避免复用已删主键");
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    @DisplayName("upsert：首次配置（无已有行）→ 不得调用 deleteById")
    void upsertFirstTimeDoesNotDelete() {
        when(groupEncMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        ApiGroupEncryptionConfig in = new ApiGroupEncryptionConfig();
        in.setMode(ApiGroupEncryptionConfig.MODE_DISABLED);

        service.upsert(5L, in);

        verify(groupEncMapper, never()).deleteById(anyLong());
        verify(groupEncMapper, times(1)).insert(any(ApiGroupEncryptionConfig.class));
    }

    @Test
    @DisplayName("🔴 upsert：改为 DISABLED → 必须把入参/返参的算法、密钥、IV、填充全部清空（防「幽灵密钥」）")
    void upsertDisabledWipesKeyMaterial() {
        when(groupEncMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(enabled(90L, 5L)));

        // 模拟前端只改了 mode，其余字段仍是旧的（真实场景：用户从"启用"切到"不需要"）
        ApiGroupEncryptionConfig in = enabled(90L, 5L);
        in.setMode(ApiGroupEncryptionConfig.MODE_DISABLED);

        service.upsert(5L, in);

        ArgumentCaptor<ApiGroupEncryptionConfig> cap =
                ArgumentCaptor.forClass(ApiGroupEncryptionConfig.class);
        verify(groupEncMapper).insert(cap.capture());
        ApiGroupEncryptionConfig saved = cap.getValue();

        assertEquals(ApiGroupEncryptionConfig.MODE_DISABLED, saved.getMode());
        assertNull(saved.getRequestKey(), "DISABLED 必须清空入参密钥，否则旧密钥会随 mode 改回而复活");
        assertNull(saved.getRequestAlgorithm());
        assertNull(saved.getRequestMode());
        assertNull(saved.getRequestIv());
        assertNull(saved.getRequestPadding());
        assertNull(saved.getResponseKey());
        assertNull(saved.getResponseAlgorithm());
        assertNull(saved.getResponseMode());
        assertNull(saved.getResponseIv());
        assertNull(saved.getResponsePadding());
        assertEquals(Boolean.FALSE, saved.getRequestEncrypted());
        assertEquals(Boolean.FALSE, saved.getResponseEncrypted());
    }

    @Test
    @DisplayName("🔴 upsert：改为 INHERIT 也必须清空密钥材料（继承态是「本分组不定义」，不该留痕）")
    void upsertInheritWipesKeyMaterial() {
        when(groupEncMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        ApiGroupEncryptionConfig in = enabled(null, 5L);
        in.setMode(ApiGroupEncryptionConfig.MODE_INHERIT);

        service.upsert(5L, in);

        ArgumentCaptor<ApiGroupEncryptionConfig> cap =
                ArgumentCaptor.forClass(ApiGroupEncryptionConfig.class);
        verify(groupEncMapper).insert(cap.capture());
        assertNull(cap.getValue().getRequestKey());
        assertNull(cap.getValue().getResponseKey());
    }

    @Test
    @DisplayName("upsert：未知 mode 一律归为 INHERIT 并清空密钥（最保守，不误开加密）")
    void upsertUnknownModeFallsBackToInherit() {
        when(groupEncMapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        ApiGroupEncryptionConfig in = enabled(null, 5L);
        in.setMode("SOMETHING_ELSE");

        service.upsert(5L, in);

        ArgumentCaptor<ApiGroupEncryptionConfig> cap =
                ArgumentCaptor.forClass(ApiGroupEncryptionConfig.class);
        verify(groupEncMapper).insert(cap.capture());
        assertEquals(ApiGroupEncryptionConfig.MODE_INHERIT, cap.getValue().getMode());
        assertNull(cap.getValue().getRequestKey(), "未知 mode 不得保留密钥并被当成启用");
    }

    // ===================== updateById =====================

    @Test
    @DisplayName("updateById：行不存在 → 404")
    void updateMissingRowThrows404() {
        when(groupEncMapper.selectById(77L)).thenReturn(null);
        assertEquals(404, assertThrows(GatewayException.class,
                () -> service.updateById(77L, enabled(77L, 5L))).getCode());
        verify(groupEncMapper, never()).insert(any());
    }

    @Test
    @DisplayName("updateById：id 为 null → 400")
    void updateNullIdThrows400() {
        assertEquals(400, assertThrows(GatewayException.class,
                () -> service.updateById(null, enabled(null, 5L))).getCode());
    }

    @Test
    @DisplayName("🔴 updateById：归属分组不可改（请求体里的 groupId 被忽略，沿用库里的值）")
    void updateKeepsOriginalGroupId() {
        ApiGroupEncryptionConfig existing = enabled(77L, 5L);
        existing.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        when(groupEncMapper.selectById(77L)).thenReturn(existing);

        ApiGroupEncryptionConfig in = enabled(null, 999L);   // 恶意/错误的 groupId
        service.updateById(77L, in);

        verify(groupEncMapper).deleteById(77L);
        ArgumentCaptor<ApiGroupEncryptionConfig> cap =
                ArgumentCaptor.forClass(ApiGroupEncryptionConfig.class);
        verify(groupEncMapper).insert(cap.capture());
        ApiGroupEncryptionConfig saved = cap.getValue();
        assertEquals(Long.valueOf(5L), saved.getGroupId(), "归属分组必须沿用原值，不能被请求体改走");
        assertNull(saved.getId(), "先删后插 => 新行 id 必须为空以重新分配");
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), saved.getCreatedAt(), "createdAt 应保留原值");
    }

    // ===================== deleteById =====================

    @Test
    @DisplayName("deleteById：行不存在 → 404（不做静默成功，避免「以为删了其实没删」）")
    void deleteMissingRowThrows404() {
        when(groupEncMapper.selectById(88L)).thenReturn(null);
        assertEquals(404, assertThrows(GatewayException.class,
                () -> service.deleteById(88L)).getCode());
        verify(groupEncMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("deleteById：id 为 null → 400")
    void deleteNullIdThrows400() {
        assertEquals(400, assertThrows(GatewayException.class, () -> service.deleteById(null)).getCode());
    }

    @Test
    @DisplayName("deleteById：存在则删除")
    void deleteExistingRowWorks() {
        when(groupEncMapper.selectById(88L)).thenReturn(enabled(88L, 5L));
        service.deleteById(88L);
        verify(groupEncMapper).deleteById(88L);
        verify(groupEncMapper, never()).insert(any());
    }

    // ===================== fixtures =====================

    private ApiGroupEncryptionConfig enabled(Long id, Long groupId) {
        ApiGroupEncryptionConfig c = new ApiGroupEncryptionConfig();
        c.setId(id);
        c.setGroupId(groupId);
        c.setMode(ApiGroupEncryptionConfig.MODE_ENABLED);
        c.setRequestEncrypted(true);
        c.setRequestAlgorithm("SM4");
        c.setRequestMode("ECB");
        c.setRequestKey("OLD-KEY");
        c.setRequestIv("OLD-IV");
        c.setRequestPadding("PKCS5Padding");
        c.setResponseEncrypted(true);
        c.setResponseAlgorithm("SM4");
        c.setResponseMode("ECB");
        c.setResponseKey("OLD-KEY-RESP");
        c.setResponseIv("OLD-IV-RESP");
        c.setResponsePadding("PKCS5Padding");
        return c;
    }
}
