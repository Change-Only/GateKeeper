package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.AppIpWhitelist;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.AppIpWhitelistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * IpWhitelistHandler 单元测试 — T15-4（应用级 IP 白名单）
 *
 * <p><b>本测试补的是 T15-4 修掉的那个缺陷</b>：此前查询不带 {@code status} 条件，
 * 「停用」形同虚设 —— 被停用的条目依然在拦人，用户想把某段 IP 临时摘掉只能删记录。
 * 现在查询固定带 {@code status = 1}，因此这里除了通过/拦截的行为，
 * 还<b>直接断言查询条件里出现 status</b>：这是防止有人日后"顺手"把过滤去掉的护栏。</p>
 *
 * <p>与 {@link SysAccessWhitelistHandler}（系统级，@Order(0)）是「且」的关系：
 * 本环节依赖 AppAuthHandler 已写入 appId；appId 为 null 时直接跳过（交给后续环节）。</p>
 *
 * @author GateKeeper
 * @since T15-4 (2026-09-15)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IpWhitelistHandler：启用过滤 / 命中放行 / 非命中 403 / appId 为空跳过")
class IpWhitelistHandlerTest {

    @Mock
    private AppIpWhitelistMapper mapper;

    private IpWhitelistHandler handler;

    @BeforeEach
    void setUp() {
        handler = new IpWhitelistHandler(mapper);
    }

    private GatewayContext ctx(Long appId, String ip) {
        GatewayContext ctx = new GatewayContext();
        ctx.setAppId(appId);
        ctx.setClientIp(ip);
        return ctx;
    }

    private AppIpWhitelist row(String cidr) {
        AppIpWhitelist r = new AppIpWhitelist();
        r.setAppId(1L);
        r.setIpCidr(cidr);
        r.setStatus(AppIpWhitelist.STATUS_ENABLED);
        return r;
    }

    @Test
    @DisplayName("appId 为空 ⇒ 直接跳过，且不查库（应用未识别交给后续环节）")
    void skipsWhenAppIdNull() {
        assertDoesNotThrow(() -> handler.handle(ctx(null, "10.0.0.1")));
        Mockito.verify(mapper, never()).selectList(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("无启用规则 ⇒ 不限制")
    void emptyMeansUnrestricted() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        assertDoesNotThrow(() -> handler.handle(ctx(1L, "10.0.0.1")));
    }

    @Test
    @DisplayName("命中 CIDR 网段 ⇒ 放行")
    void matchingCidrPasses() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row("10.0.0.0/24")));
        assertDoesNotThrow(() -> handler.handle(ctx(1L, "10.0.0.55")));
    }

    @Test
    @DisplayName("多条规则命中任意一条 ⇒ 放行")
    void anyRuleMatchPasses() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Arrays.asList(row("172.16.0.0/12"), row("192.168.1.10")));
        assertDoesNotThrow(() -> handler.handle(ctx(1L, "192.168.1.10")));
    }

    @Test
    @DisplayName("非命中 ⇒ 403，且置 blocked + blockReason")
    void nonMatchingBlocks() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row("10.0.0.0/24")));

        GatewayContext ctx = ctx(1L, "203.0.113.9");
        GatewayException ex = assertThrows403(() -> handler.handle(ctx));

        assertEquals(403, ex.getCode());
        assertTrue(ctx.isBlocked());
        assertEquals("IP not in whitelist", ctx.getBlockReason());
    }

    @Test
    @DisplayName("clientIp 为 null 且存在启用规则 ⇒ 拒绝（不得因取不到 IP 就放行）")
    void nullClientIpBlocked() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row("10.0.0.0/24")));
        assertEquals(403, assertThrows403(() -> handler.handle(ctx(1L, null))).getCode());
    }

    @Test
    @DisplayName("🔴 查询条件必须同时含 app_id 与 status（停用条目不参与校验的落点）")
    void queryFiltersByAppAndStatus() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        handler.handle(ctx(1L, "10.0.0.1"));

        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        Mockito.verify(mapper).selectList(cap.capture());
        String seg = cap.getValue().getSqlSegment();
        assertTrue(seg.contains("app_id"), "必须按 app_id 过滤，实际：" + seg);
        assertTrue(seg.contains("status"), "必须按 status 过滤（T15-4 修复点），实际：" + seg);
    }

    private static GatewayException assertThrows403(Runnable r) {
        return org.junit.jupiter.api.Assertions.assertThrows(GatewayException.class, r::run);
    }
}
