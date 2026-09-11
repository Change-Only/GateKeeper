package com.gatekeeper.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感数据脱敏工具测试
 * 覆盖：手机号、身份证、银行卡、邮箱、Token/密钥键值对
 */
class DesensitizeUtilTest {

    /** 手机号：138****8000 */
    @Test
    void shouldMaskPhone() {
        String masked = DesensitizeUtil.mask("手机号：13812345678");
        assertTrue(masked.contains("138****5678"), "实际结果：" + masked);
        assertFalse(masked.contains("1234"));
    }

    /** 身份证号：保留前 6 位与后 4 位 */
    @Test
    void shouldMaskIdCard() {
        String masked = DesensitizeUtil.mask("\"idCard\":\"110101199001011234\"");
        assertTrue(masked.contains("110101***********1234"), "实际结果：" + masked);
    }

    /** 银行卡号：6222 **** **** 1234 */
    @Test
    void shouldMaskBankCard() {
        String masked = DesensitizeUtil.mask("card=6222021234567890123");
        assertTrue(masked.contains("****"), "实际结果：" + masked);
        assertFalse(masked.contains("3456789"));
    }

    /** 邮箱：保留首字符与域名 */
    @Test
    void shouldMaskEmail() {
        String masked = DesensitizeUtil.mask("contact: zhangsanfeng@example.com");
        assertTrue(masked.contains("@example.com"), "实际结果：" + masked);
        assertFalse(masked.contains("zhangsanfeng"));
    }

    /** Token / AppSecret 等键值对：值部分打码 */
    @Test
    void shouldMaskTokenLikeValues() {
        String masked = DesensitizeUtil.mask("{\"appSecret\":\"abcdefghijklmnop\"}");
        assertTrue(masked.contains("abcd****mnop"), "实际结果：" + masked);
        assertFalse(masked.contains("efghijkl"));

        String pwd = DesensitizeUtil.mask("password=MyP@ssw0rd2026");
        assertTrue(pwd.contains("****"), "实际结果：" + pwd);
        assertFalse(pwd.contains("ssw0rd"));
    }

    /** 空值与无敏感信息文本应安全返回 */
    @Test
    void shouldHandleNullAndPlainText() {
        assertNull(DesensitizeUtil.mask(null));
        assertEquals("", DesensitizeUtil.mask(""));
        assertEquals("订单创建成功", DesensitizeUtil.mask("订单创建成功"));
    }
}
