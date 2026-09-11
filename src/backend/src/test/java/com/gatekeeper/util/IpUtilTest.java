package com.gatekeeper.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IP/CIDR 匹配工具测试
 * 覆盖：单 IP 精确匹配、标准网段匹配、边界网段、IPv6 与非法输入
 */
class IpUtilTest {

    /** 单 IP（无 / 前缀）应退化为精确比较 */
    @Test
    void shouldMatchSingleIpExactly() {
        assertTrue(IpUtil.isIpInCidr("192.168.1.10", "192.168.1.10"));
        assertFalse(IpUtil.isIpInCidr("192.168.1.11", "192.168.1.10"));
    }

    /** /24 网段：同网段放行、跨网段拒绝 */
    @Test
    void shouldMatchCidr24() {
        assertTrue(IpUtil.isIpInCidr("192.168.1.1", "192.168.1.0/24"));
        assertTrue(IpUtil.isIpInCidr("192.168.1.254", "192.168.1.0/24"));
        assertFalse(IpUtil.isIpInCidr("192.168.2.1", "192.168.1.0/24"));
    }

    /** /8 与 /16 网段 */
    @Test
    void shouldMatchCidr8And16() {
        assertTrue(IpUtil.isIpInCidr("10.20.30.40", "10.0.0.0/8"));
        assertFalse(IpUtil.isIpInCidr("11.20.30.40", "10.0.0.0/8"));
        assertTrue(IpUtil.isIpInCidr("172.16.5.9", "172.16.0.0/16"));
        assertFalse(IpUtil.isIpInCidr("172.17.5.9", "172.16.0.0/16"));
    }

    /** 非 8 整数倍的网段（/28）应按位掩码正确匹配 */
    @Test
    void shouldMatchPartialPrefix() {
        assertTrue(IpUtil.isIpInCidr("192.168.1.5", "192.168.1.0/28"));
        assertTrue(IpUtil.isIpInCidr("192.168.1.15", "192.168.1.0/28"));
        assertFalse(IpUtil.isIpInCidr("192.168.1.16", "192.168.1.0/28"));
    }

    /** IPv4 与 IPv6 不应互配；非法输入安全返回 false */
    @Test
    void shouldHandleIpv6AndInvalidInput() {
        assertFalse(IpUtil.isIpInCidr("2001:db8::1", "192.168.1.0/24"));
        assertTrue(IpUtil.isIpInCidr("2001:db8::1", "2001:db8::/32"));
        assertFalse(IpUtil.isIpInCidr("not-an-ip", "192.168.1.0/24"));
        assertFalse(IpUtil.isIpInCidr(null, "192.168.1.0/24"));
        assertFalse(IpUtil.isIpInCidr("192.168.1.1", null));
    }
}
