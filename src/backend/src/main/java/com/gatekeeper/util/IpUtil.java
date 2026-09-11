package com.gatekeeper.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * IP/CIDR 匹配工具
 */
public class IpUtil {

    /**
     * 检查IP是否在CIDR范围内
     * 支持单IP（无 / 前缀）精确匹配，以及 IPv4/IPv6 的 CIDR 网段匹配
     *
     * @param ip   待校验的IP地址
     * @param cidr CIDR 网段或单IP
     * @return IP 在范围内返回 true，否则返回 false
     */
    public static boolean isIpInCidr(String ip, String cidr) {
        if (ip == null || cidr == null) return false;

        // 如果不是CIDR格式，退化为单IP精确比较
        if (!cidr.contains("/")) {
            return ip.equals(cidr);
        }

        try {
            String[] parts = cidr.split("/");
            String network = parts[0];
            int prefix = Integer.parseInt(parts[1]);

            byte[] ipBytes = InetAddress.getByName(ip).getAddress();
            byte[] cidrBytes = InetAddress.getByName(network).getAddress();

            // IPv4 与 IPv6 字节长度不同，必然不匹配
            if (ipBytes.length != cidrBytes.length) return false;

            int fullBytes = prefix / 8;
            int partialBits = prefix % 8;

            // 先比较完整前缀字节，任一位不同即不在网段内
            for (int i = 0; i < fullBytes; i++) {
                if (ipBytes[i] != cidrBytes[i]) return false;
            }

            // 再比较不足一个字节的部分位，通过掩码屏蔽无关低位
            if (partialBits > 0 && fullBytes < ipBytes.length) {
                int mask = 0xFF << (8 - partialBits);
                if ((ipBytes[fullBytes] & mask) != (cidrBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (UnknownHostException e) {
            // IP 或网段解析失败，视为不匹配
            return false;
        }
    }
}
