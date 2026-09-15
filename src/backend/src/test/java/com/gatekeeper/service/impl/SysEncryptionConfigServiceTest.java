package com.gatekeeper.service.impl;

import com.gatekeeper.entity.SysEncryptionConfig;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.SysEncryptionConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SysEncryptionConfigServiceImpl 单元测试 — T16-1
 *
 * <p><b>本测试的重点有三个，缺一不可：</b></p>
 *
 * <p><b>① 缺行 = 启用。</b> SQL 脚本刻意不播种任何行，靠"读不到就当启用"实现对存量零影响。
 * 这条一旦被改成"缺行=明文"，所有没跑过脚本的环境会在升级瞬间静默降级为明文。</p>
 *
 * <p><b>② fail-safe 方向必须与访问白名单相反。</b>
 * 白名单查询异常时 fail-open（放行）—— 它防的是"误拦"；
 * 本开关是「把全平台降级为明文」的闸门，查询异常时<b>必须保持加密</b>。
 * 方向写反了，一次 DB 抖动就会静默泄漏明文。所以这里专门钉一条
 * 「mapper 抛异常 ⇒ 返回 true」。</p>
 *
 * <p><b>③ 写后必须失效缓存。</b> 缓存 TTL 10s 是为了省网关热路径的一次 DB 往返，
 * 但用户点了保存必须立刻生效；写后不失效会出现"保存成功了，行为还是旧的"。
 * 测试用「改库值 → 断言仍是缓存旧值 → invalidate/写后 → 断言取到新值」证明缓存真的在起作用，
 * 而不是让断言被一个恒回源的实现蒙混过关。</p>
 *
 * @author GateKeeper
 * @since T16-1 (2026-09-15)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysEncryptionConfigService：缺行=启用 / fail-safe 保持加密 / 缓存与写后失效")
class SysEncryptionConfigServiceTest {

    @Mock
    private SysEncryptionConfigMapper mapper;

    private SysEncryptionConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SysEncryptionConfigServiceImpl(mapper);
    }

    /** 造一行配置 */
    private SysEncryptionConfig row(Integer enabled) {
        SysEncryptionConfig r = new SysEncryptionConfig();
        r.setId(SysEncryptionConfig.SINGLETON_ID);
        r.setEnabled(enabled);
        return r;
    }

    // ===================== 缺行语义 =====================

    @Test
    @DisplayName("isGloballyEnabled：表中无行 ⇒ 视为启用（零迁移的关键）")
    void missingRowMeansEnabled() {
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(null);
        assertTrue(service.isGloballyEnabled(), "缺行必须等于启用，否则升级会静默降级为明文");
    }

    @Test
    @DisplayName("isGloballyEnabled：enabled 列为 NULL ⇒ 视为启用（不被 null 带偏）")
    void nullEnabledMeansEnabled() {
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(null));
        assertTrue(service.isGloballyEnabled());
    }

    @Test
    @DisplayName("isGloballyEnabled：enabled=1 ⇒ 启用")
    void enabledOneMeansEnabled() {
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(1));
        assertTrue(service.isGloballyEnabled());
    }

    @Test
    @DisplayName("isGloballyEnabled：enabled=0 ⇒ 全局明文")
    void enabledZeroMeansPlaintext() {
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(0));
        assertFalse(service.isGloballyEnabled());
    }

    // ===================== fail-safe =====================

    @Test
    @DisplayName("🔴 isGloballyEnabled：查询抛异常 ⇒ 保持加密（fail-safe，与白名单 fail-open 相反）")
    void mapperErrorKeepsEncryption() {
        // 一律用 doThrow/doReturn 打桩：`when(mock.foo())` 会**先真的调用** mock.foo()，
        // 若该方法已被 stub 成抛异常，异常会在打桩语句里就冒出来，测试直接炸。
        doThrow(new RuntimeException("connection reset"))
                .when(mapper).selectById(SysEncryptionConfig.SINGLETON_ID);
        assertTrue(service.isGloballyEnabled(),
                "读不到开关时必须当它是启用的：开关是「降级为明文」的闸门，"
                        + "不能因为一次 DB 抖动把全平台打成明文");
    }

    @Test
    @DisplayName("isGloballyEnabled：异常后不能污染缓存（下次仍要回源）")
    void errorDoesNotPoisonCache() {
        doThrow(new RuntimeException("boom"))
                .when(mapper).selectById(SysEncryptionConfig.SINGLETON_ID);
        assertTrue(service.isGloballyEnabled());

        // 恢复后库中是「关闭」，必须能读到 —— 若异常路径把 true 写进了缓存，这里会失败
        doReturn(row(0)).when(mapper).selectById(SysEncryptionConfig.SINGLETON_ID);
        assertFalse(service.isGloballyEnabled(),
                "异常路径不应把默认值写进缓存，否则开关永远读不出来");
    }

    // ===================== 缓存 =====================

    @Test
    @DisplayName("isGloballyEnabled：TTL 内命中缓存，不重复回源")
    void cacheHitAvoidsSecondQuery() {
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(1));
        assertTrue(service.isGloballyEnabled());
        assertTrue(service.isGloballyEnabled());
        assertTrue(service.isGloballyEnabled());
        verify(mapper, times(1)).selectById(SysEncryptionConfig.SINGLETON_ID);
    }

    @Test
    @DisplayName("🔴 invalidate 后必须重新回源（证明缓存真的在拦，而不是每次都查库）")
    void invalidateForcesReload() {
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(1));
        assertTrue(service.isGloballyEnabled());

        // 库里改成「关闭」；未失效前应仍按缓存返回 true
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(0));
        assertTrue(service.isGloballyEnabled(), "10s 内应命中缓存");

        service.invalidate();
        assertFalse(service.isGloballyEnabled(), "失效后必须取到库里的最新值");
    }

    // ===================== get =====================

    @Test
    @DisplayName("get：缺行 ⇒ 返回 enabled=1 的虚拟行，且不落库")
    void getReturnsVirtualRowWhenMissing() {
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(null);
        SysEncryptionConfig got = service.get();
        assertNotNull(got);
        assertEquals(SysEncryptionConfig.SINGLETON_ID, got.getId().longValue());
        assertEquals(Integer.valueOf(SysEncryptionConfig.ENABLED_ON), got.getEnabled(),
                "缺行时给前端渲染的默认值必须是「启用」");
        verify(mapper, never()).insert(any(SysEncryptionConfig.class));
    }

    @Test
    @DisplayName("get：有行 ⇒ 原样返回")
    void getReturnsExistingRow() {
        SysEncryptionConfig r = row(0);
        r.setRemark("临时排查");
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(r);
        SysEncryptionConfig got = service.get();
        assertEquals(Integer.valueOf(0), got.getEnabled());
        assertEquals("临时排查", got.getRemark());
    }

    // ===================== updateEnabled =====================

    @Test
    @DisplayName("updateEnabled：首次保存（无行）⇒ insert 且主键固定为 1")
    void updateInsertsSingletonRow() {
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(null);

        service.updateEnabled(SysEncryptionConfig.ENABLED_OFF, 7L, "灰度排查");

        ArgumentCaptor<SysEncryptionConfig> cap = ArgumentCaptor.forClass(SysEncryptionConfig.class);
        verify(mapper).insert(cap.capture());
        SysEncryptionConfig saved = cap.getValue();
        assertEquals(SysEncryptionConfig.SINGLETON_ID, saved.getId().longValue(), "单行表主键必须固定为 1");
        assertEquals(Integer.valueOf(0), saved.getEnabled());
        assertEquals(Long.valueOf(7L), saved.getUpdatedBy());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    @DisplayName("updateEnabled：已有行 ⇒ 走 update（不重复 insert）")
    void updateUsesUpdateWhenRowExists() {
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(1));
        service.updateEnabled(SysEncryptionConfig.ENABLED_OFF, 7L, null);
        verify(mapper, never()).insert(any(SysEncryptionConfig.class));
        verify(mapper).update(any(), any());
    }

    @Test
    @DisplayName("🔴 updateEnabled：写后缓存立刻失效 ⇒ 保存即生效")
    void updateInvalidatesCacheImmediately() {
        // 先让缓存里是「启用」
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(1));
        assertTrue(service.isGloballyEnabled());

        // 保存为「关闭」，并把库读改成 0
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(0));
        service.updateEnabled(SysEncryptionConfig.ENABLED_OFF, 7L, "关闭");

        assertFalse(service.isGloballyEnabled(),
                "用户点了保存必须立刻生效，不能等 10s TTL 到期");
    }

    @Test
    @DisplayName("updateEnabled：非法值（null / 2）必须拒绝且不写库")
    void updateRejectsIllegalValues() {
        assertThrows(GatewayException.class, () -> service.updateEnabled(null, 1L, null));
        assertThrows(GatewayException.class, () -> service.updateEnabled(2, 1L, null));
        assertThrows(GatewayException.class, () -> service.updateEnabled(-1, 1L, null));
        verify(mapper, never()).insert(any(SysEncryptionConfig.class));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("updateEnabled：返回值反映落库后的真实状态")
    void updateReturnsPersistedState() {
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(1));
        SysEncryptionConfig after = service.updateEnabled(SysEncryptionConfig.ENABLED_ON, 9L, null);
        clearInvocations(mapper);
        assertNotNull(after);
        assertEquals(Integer.valueOf(SysEncryptionConfig.ENABLED_ON), after.getEnabled());
    }

    @Test
    @DisplayName("invalidate：可重复调用，且调用后必然回源")
    void invalidateIsIdempotent() {
        service.invalidate();
        service.invalidate();
        when(mapper.selectById(SysEncryptionConfig.SINGLETON_ID)).thenReturn(row(0));
        assertFalse(service.isGloballyEnabled());
    }
}
