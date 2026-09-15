package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.gatekeeper.entity.SysIpWhitelist;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.SysIpWhitelistMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SysIpWhitelistServiceImpl 单元测试 — T15-4
 *
 * <p><b>本测试的重点是「写前把 IP/CIDR 校验死」</b>。
 * 白名单是强语义配置（要么放行、要么拦死），而一个拼错的 CIDR
 * 在运行期<b>没有任何报错</b>，只是"永远匹配不上"——
 * 用户会以为配好了，直到发现公司内网被拒（或以为限制了其实没限制）。
 * 所以格式、前缀范围、重复、主机名（localhost）全部钉死在断言里。</p>
 *
 * <p>第二重点是更新路径必须用 {@link UpdateWrapper} 显式 set 四个字段：
 * {@code updateById} 的 NOT_NULL 策略会让"把备注清空"静默失效，
 * 而 IP/CIDR 与 status 也必须真的被写进去（否则"停用了却没停"）。</p>
 *
 * @author GateKeeper
 * @since T15-4 (2026-09-15)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SysIpWhitelistService：CIDR 校验 / 唯一性 / 显式 set 更新")
class SysIpWhitelistServiceTest {

    @Mock
    private SysIpWhitelistMapper mapper;

    private SysIpWhitelistServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SysIpWhitelistServiceImpl(mapper);
    }

    // ===================== 新增：默认值 =====================

    @Test
    @DisplayName("add：合法 CIDR → 补默认状态为启用、清空 id、写入时间")
    void addAppliesDefaults() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        SysIpWhitelist in = new SysIpWhitelist();
        in.setId(999L);              // 恶意/残留 id，必须被清掉
        in.setIpCidr("  10.0.0.0/24  ");  // 首尾空白必须被 trim

        service.add(in);

        ArgumentCaptor<SysIpWhitelist> cap = ArgumentCaptor.forClass(SysIpWhitelist.class);
        verify(mapper).insert(cap.capture());
        SysIpWhitelist saved = cap.getValue();
        assertNull(saved.getId(), "插入前必须清空 id");
        assertEquals("10.0.0.0/24", saved.getIpCidr(), "首尾空白必须去掉，否则与来源 IP 永远匹配不上");
        assertEquals(Integer.valueOf(SysIpWhitelist.STATUS_ENABLED), saved.getStatus(), "未指定状态时默认启用");
        assertNotNull(saved.getCreatedAt());
        assertNull(saved.getRemark(), "空白备注应归一为 null，而不是空串");
    }

    @Test
    @DisplayName("add：显式 status=0（停用）必须被保留，不能被默认值覆盖")
    void addKeepsExplicitDisabled() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("10.0.0.1");
        in.setStatus(SysIpWhitelist.STATUS_DISABLED);

        service.add(in);

        ArgumentCaptor<SysIpWhitelist> cap = ArgumentCaptor.forClass(SysIpWhitelist.class);
        verify(mapper).insert(cap.capture());
        assertEquals(Integer.valueOf(0), cap.getValue().getStatus());
    }

    @Test
    @DisplayName("add：单 IP（无 / 前缀）合法")
    void addAcceptsPlainIp() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());
        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("192.168.1.10");
        service.add(in);
        verify(mapper).insert(any(SysIpWhitelist.class));
    }

    // ===================== 新增：校验 =====================

    @Test
    @DisplayName("🔴 add：主机名（localhost）必须被拒 —— InetAddress 能解析它，但它永远匹配不上来源 IP")
    void addRejectsHostname() {
        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("localhost");

        assertEquals(400, assertThrows(GatewayException.class, () -> service.add(in)).getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("add：IPv4 段值超 255 → 400")
    void addRejectsOutOfRangeOctet() {
        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("999.1.1.1");
        assertEquals(400, assertThrows(GatewayException.class, () -> service.add(in)).getCode());
    }

    @Test
    @DisplayName("add：CIDR 前缀超范围（/33）→ 400，且错误信息说明范围")
    void addRejectsPrefixOutOfRange() {
        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("10.0.0.0/33");

        GatewayException ex = assertThrows(GatewayException.class, () -> service.add(in));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("0-32"), "错误信息应指明合法范围，当前：" + ex.getMessage());
    }

    @Test
    @DisplayName("add：CIDR 前缀非数字（写成 10.0.0.0-24 的常见笔误）→ 400")
    void addRejectsNonNumericPrefix() {
        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("10.0.0.0-24");
        assertEquals(400, assertThrows(GatewayException.class, () -> service.add(in)).getCode());
    }

    @Test
    @DisplayName("add：CIDR 前缀为空（10.0.0.0/）→ 400")
    void addRejectsEmptyPrefix() {
        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("10.0.0.0/");
        assertEquals(400, assertThrows(GatewayException.class, () -> service.add(in)).getCode());
    }

    @Test
    @DisplayName("add：IP 为空 / 空白 / 纯空格 → 400")
    void addRejectsBlank() {
        for (String v : Arrays.asList(null, "", "   ")) {
            SysIpWhitelist in = new SysIpWhitelist();
            in.setIpCidr(v);
            assertEquals(400, assertThrows(GatewayException.class, () -> service.add(in)).getCode(),
                    "空白值应被拒：" + v);
        }
    }

    @Test
    @DisplayName("add：入参对象为 null → 400")
    void addRejectsNullEntry() {
        assertEquals(400, assertThrows(GatewayException.class, () -> service.add(null)).getCode());
    }

    @Test
    @DisplayName("🔴 add：重复 CIDR → 提前给 400 可读错误（不依赖 DB 唯一键抛 500）")
    void addRejectsDuplicate() {
        SysIpWhitelist exists = new SysIpWhitelist();
        exists.setId(7L);
        exists.setIpCidr("10.0.0.0/24");
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(exists));

        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("10.0.0.0/24");

        GatewayException ex = assertThrows(GatewayException.class, () -> service.add(in));
        assertEquals(400, ex.getCode());
        verify(mapper, never()).insert(any());
    }

    // ===================== 更新 =====================

    @Test
    @DisplayName("updateById：行不存在 → 404（不做静默成功）")
    void updateMissingThrows404() {
        when(mapper.selectById(9L)).thenReturn(null);
        assertEquals(404, assertThrows(GatewayException.class,
                () -> service.updateById(9L, new SysIpWhitelist())).getCode());
        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("updateById：id 为 null → 400")
    void updateNullIdThrows400() {
        assertEquals(400, assertThrows(GatewayException.class,
                () -> service.updateById(null, new SysIpWhitelist())).getCode());
    }

    @Test
    @DisplayName("🔴 updateById：重复 CIDR（除自己外）→ 400")
    void updateRejectsDuplicateOfOther() {
        SysIpWhitelist self = new SysIpWhitelist();
        self.setId(9L);
        self.setIpCidr("10.0.0.1");
        when(mapper.selectById(9L)).thenReturn(self);

        SysIpWhitelist other = new SysIpWhitelist();
        other.setId(10L);
        other.setIpCidr("10.0.0.0/24");
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(other));

        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("10.0.0.0/24");

        assertEquals(400, assertThrows(GatewayException.class, () -> service.updateById(9L, in)).getCode());
    }

    @Test
    @DisplayName("updateById：CIDR 与自己相同 → 允许（编辑备注不应被唯一性误拦）")
    void updateAllowsSameCidrAsSelf() {
        SysIpWhitelist self = new SysIpWhitelist();
        self.setId(9L);
        self.setIpCidr("10.0.0.0/24");
        when(mapper.selectById(9L)).thenReturn(self);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(self));

        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("10.0.0.0/24");
        in.setRemark("改了备注");

        service.updateById(9L, in);

        verify(mapper).update(ArgumentMatchers.<SysIpWhitelist>isNull(),
                ArgumentMatchers.<Wrapper<SysIpWhitelist>>any());
    }

    @Test
    @DisplayName("🔴 updateById：必须用显式 set 写 ip_cidr / remark / status / updated_at 四个字段")
    void updateUsesExplicitSetForAllFourColumns() {
        SysIpWhitelist self = new SysIpWhitelist();
        self.setId(9L);
        self.setIpCidr("10.0.0.1");
        when(mapper.selectById(9L)).thenReturn(self);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(self));

        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("10.0.0.0/24");
        in.setRemark(null);              // 清空备注
        in.setStatus(SysIpWhitelist.STATUS_DISABLED);

        service.updateById(9L, in);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<SysIpWhitelist>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(ArgumentMatchers.<SysIpWhitelist>isNull(), cap.capture());
        String setClause = ((UpdateWrapper<SysIpWhitelist>) cap.getValue()).getSqlSet();
        // 显式 set 才能把 null 写进库（updateById 的 NOT_NULL 策略做不到）
        assertTrue(setClause.contains("ip_cidr"), "必须 set ip_cidr，实际：" + setClause);
        assertTrue(setClause.contains("remark"), "必须 set remark（允许写回 null），实际：" + setClause);
        assertTrue(setClause.contains("status"), "必须 set status（停用要真的生效），实际：" + setClause);
        assertTrue(setClause.contains("updated_at"), "必须 set updated_at，实际：" + setClause);
    }

    @Test
    @DisplayName("updateById：status 为 null → 按启用处理（避免历史行被误判为停用）")
    void updateDefaultsStatusToEnabled() {
        SysIpWhitelist self = new SysIpWhitelist();
        self.setId(9L);
        self.setIpCidr("10.0.0.1");
        when(mapper.selectById(9L)).thenReturn(self);
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.emptyList());

        SysIpWhitelist in = new SysIpWhitelist();
        in.setIpCidr("10.0.0.1");
        in.setStatus(null);

        service.updateById(9L, in);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<SysIpWhitelist>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(ArgumentMatchers.<SysIpWhitelist>isNull(), cap.capture());
        Object status = ((UpdateWrapper<SysIpWhitelist>) cap.getValue())
                .getParamNameValuePairs().values().stream()
                .filter(v -> Integer.valueOf(1).equals(v)).findFirst().orElse(null);
        assertEquals(Integer.valueOf(1), status, "status 为 null 时应写入启用值 1");
    }

    // ===================== 删除 =====================

    @Test
    @DisplayName("deleteById：行不存在 → 404")
    void deleteMissingThrows404() {
        when(mapper.selectById(9L)).thenReturn(null);
        assertEquals(404, assertThrows(GatewayException.class, () -> service.deleteById(9L)).getCode());
        // 注意：MyBatis-Plus 3.5.5 的 BaseMapper 同时有 deleteById(Serializable) 与 deleteById(T)，
        // 裸 any() 会因两个重载都匹配而编译失败，必须显式锁定 Serializable 那个重载。
        verify(mapper, never()).deleteById(ArgumentMatchers.<Serializable>any());
    }

    @Test
    @DisplayName("deleteById：存在则删除")
    void deleteWorks() {
        SysIpWhitelist self = new SysIpWhitelist();
        self.setId(9L);
        when(mapper.selectById(9L)).thenReturn(self);
        service.deleteById(9L);
        verify(mapper).deleteById(9L);
    }

    // ===================== 查询 =====================

    @Test
    @DisplayName("listEnabled：查询条件必须带 status=1 —— 这是「停用即不生效」的落点")
    void listEnabledFiltersByStatus() {
        SysIpWhitelist row = new SysIpWhitelist();
        row.setIpCidr("10.0.0.0/24");
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(Collections.singletonList(row));

        List<SysIpWhitelist> out = service.listEnabled();

        ArgumentCaptor<QueryWrapper> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(cap.capture());
        String seg = cap.getValue().getSqlSegment();
        assertTrue(seg.contains("status"), "查询条件必须包含 status，实际：" + seg);
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("listEnabled：mapper 返回 null 时归一为空列表（调用方据此判「不限制」）")
    void listEnabledNullSafe() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(null);
        assertTrue(service.listEnabled().isEmpty());
    }

    @Test
    @DisplayName("list：mapper 返回 null 时归一为空列表")
    void listNullSafe() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(null);
        assertTrue(service.list().isEmpty());
    }
}
