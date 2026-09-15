package com.gatekeeper.gateway.handler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.entity.SysIpWhitelist;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.dto.GatewayContext;
import com.gatekeeper.mapper.SysIpWhitelistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SysAccessWhitelistHandler 单元测试 — T15-4
 *
 * <p>这是网关责任链的<b>最前一环</b>（@Order(0)），它拦错或放过的影响面是"整个系统"，
 * 因此三条行为约定必须钉死：
 * <ol>
 *   <li><b>空表 / 无启用行 ⇒ 不限制</b>（这是对存量环境零影响的前提）；</li>
 *   <li>有启用行 ⇒ 命中任一 CIDR 才放行，否则 403 且置 blocked；</li>
 *   <li>查询异常 ⇒ <b>fail-open 放行</b>（配置面故障不得升级为整个网关不可用）。</li>
 * </ol>
 * 另外断言查询条件确实带了 {@code status} —— 「停用即不生效」全靠它。</p>
 *
 * @author GateKeeper
 * @since T15-4 (2026-09-15)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysAccessWhitelistHandler：空表放行 / 命中通行 / 非命中 403 / 故障 fail-open")
class SysAccessWhitelistHandlerTest {

    @Mock
    private SysIpWhitelistMapper mapper;

    private SysAccessWhitelistHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SysAccessWhitelistHandler(mapper);
    }

    private GatewayContext ctx(String ip) {
        GatewayContext ctx = new GatewayContext();
        ctx.setClientIp(ip);
        ctx.setPath("/api/gateway/test");
        return ctx;
    }

    private SysIpWhitelist row(String cidr) {
        SysIpWhitelist r = new SysIpWhitelist();
        r.setIpCidr(cidr);
        r.setStatus(SysIpWhitelist.STATUS_ENABLED);
        return r;
    }

    @Test
    @DisplayName("① 表为空 ⇒ 不限制（对存量环境零影响的关键）")
    void emptyTableAllowsAll() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        assertDoesNotThrow(() -> handler.handle(ctx("203.0.113.9")));
    }

    @Test
    @DisplayName("①-补 查询返回 null ⇒ 同样视为不限制，不得 NPE")
    void nullResultAllowsAll() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(null);
        assertDoesNotThrow(() -> handler.handle(ctx("203.0.113.9")));
    }

    @Test
    @DisplayName("② 命中 CIDR 网段 ⇒ 放行")
    void matchingCidrPasses() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row("10.1.0.0/16")));
        assertDoesNotThrow(() -> handler.handle(ctx("10.1.2.3")));
    }

    @Test
    @DisplayName("②-补 命中单 IP 精确规则 ⇒ 放行")
    void matchingSingleIpPasses() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row("192.168.1.10")));
        assertDoesNotThrow(() -> handler.handle(ctx("192.168.1.10")));
    }

    @Test
    @DisplayName("②-补 多条规则中命中任意一条即可（短名单走 OR 语义）")
    void anyRuleMatchPasses() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Arrays.asList(row("10.0.0.0/8"), row("192.168.1.10")));
        assertDoesNotThrow(() -> handler.handle(ctx("192.168.1.10")));
    }

    @Test
    @DisplayName("③ 非命中 ⇒ 403，且置 blocked + blockReason（供调用日志与安全检测使用）")
    void nonMatchingBlocksWith403() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row("10.1.0.0/16")));

        GatewayContext ctx = ctx("203.0.113.9");
        GatewayException ex = assertThrows(GatewayException.class, () -> handler.handle(ctx));

        assertEquals(403, ex.getCode(), "系统级白名单拦截应返回 403");
        assertTrue(ctx.isBlocked(), "必须置 blocked，否则日志/安全检测拿不到被拦事实");
        assertEquals("IP not in system whitelist", ctx.getBlockReason());
    }

    @Test
    @DisplayName("③-补 clientIp 为 null 且存在启用规则 ⇒ 拒绝（不得因取不到 IP 就放行）")
    void nullClientIpIsBlocked() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row("10.1.0.0/16")));
        assertEquals(403, assertThrows(GatewayException.class,
                () -> handler.handle(ctx(null))).getCode());
    }

    @Test
    @DisplayName("🔴 查询异常 ⇒ fail-open 放行（配置面故障不得拖垮整个网关）")
    void dbFailureFailsOpen() {
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenThrow(new RuntimeException("connection refused"));
        assertDoesNotThrow(() -> handler.handle(ctx("203.0.113.9")));
    }

    @Test
    @DisplayName("🔴 查询条件必须带 status —— 「停用即不生效」全靠它")
    void queryFiltersByStatus() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        handler.handle(ctx("10.0.0.1"));

        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        org.mockito.Mockito.verify(mapper).selectList(cap.capture());
        String seg = cap.getValue().getSqlSegment();
        assertTrue(seg.contains("status"), "查询必须过滤 status，实际：" + seg);
    }
}
