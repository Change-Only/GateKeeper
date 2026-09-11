package com.gatekeeper.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏工具 — 对日志入参/响应中的敏感字段进行掩码处理
 *
 * <p>覆盖：手机号、身份证号、银行卡号、邮箱、Token/AppSecret/密码等
 * 键值对场景；规则：保留首尾，中间以 *** 替换。</p>
 */
public class DesensitizeUtil {

    /**
     * 手机号：138****8000
     * 加数字边界断言，避免在身份证/银行卡等长数字串中误匹配片段
     */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

    /**
     * 身份证号：110101***********1234（18 位）
     * 同样加数字边界断言，防止在更长数字串中部分匹配
     */
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?!\\d)");

    /**
     * 银行卡号：6222 **** **** 1234（13~19 位）
     */
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(\\d{4})\\d{8,}(\\d{4})(?!\\d)");

    /**
     * 邮箱：a***@example.com
     * 要求域名必须含点号（如 example.com），避免把含 @ 的密码等文本误判为邮箱
     */
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-]{1,3})[A-Za-z0-9._%+-]*@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)");

    /**
     * Token/AppSecret 类键值对：abcdef****wxyz
     * 值部分允许字母数字及常见特殊字符（@ # $ % 等），直到空白/引号/逗号/花括号/方括号等分隔符为止，
     * 以便对含特殊字符的密码（如 MyP@ssw0rd2026）也能正确脱敏
     */
    private static final Pattern TOKEN = Pattern.compile("(?i)(token|secret|password|passwd|pwd|appsecret)[\"']?\\s*[:=]\\s*[\"']?([^\\s\"'`,}\\]]+)");

    /**
     * 对文本中的敏感字段做掩码脱敏
     *
     * @param text 原始文本（可为 null）
     * @return 脱敏后的文本
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = PHONE.matcher(text).replaceAll("$1****$2");
        result = ID_CARD.matcher(result).replaceAll("$1***********$2");
        result = BANK_CARD.matcher(result).replaceAll("$1 **** **** $2");
        // 邮箱：保留首 1~3 字符、@ 与域名（@ 是邮箱必要分隔符，需保留以维持可读性）
        result = EMAIL.matcher(result).replaceAll("$1***@$2");
        // Token/AppSecret 类键值对：值部分打码（Java 8 兼容的 Matcher 循环替换）
        Matcher tokenMatcher = TOKEN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (tokenMatcher.find()) {
            tokenMatcher.appendReplacement(sb,
                    Matcher.quoteReplacement(tokenMatcher.group(1) + "=" + maskValue(tokenMatcher.group(2))));
        }
        tokenMatcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 对单个值做掩码：长度 ≤8 全部打码，否则保留首 4 位与末 4 位
     *
     * @param value 原始值
     * @return 掩码后的值
     */
    private static String maskValue(String value) {
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
