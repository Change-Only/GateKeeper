package com.gatekeeper.util;

import com.gatekeeper.entity.ApiCallLog;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CSV 工具单元测试（CsvUtil）
 *
 * <p>覆盖：字段转义（逗号/双引号/换行/null）、单行拼接与时间格式化，
 * 保证导出的 CSV 可被 Excel 正确解析（RFC 4180 转义 + BOM）。</p>
 */
class CsvUtilTest {

    @Test
    void escape_shouldReturnEmptyForNull() {
        assertEquals("", CsvUtil.escape(null));
    }

    @Test
    void escape_shouldQuoteValueContainingComma() {
        assertEquals("\"a,b\"", CsvUtil.escape("a,b"));
    }

    @Test
    void escape_shouldDoubleInnerQuotes() {
        assertEquals("\"say \"\"hi\"\"\"", CsvUtil.escape("say \"hi\""));
    }

    @Test
    void escape_shouldQuoteValueContainingNewline() {
        assertEquals("\"line1\nline2\"", CsvUtil.escape("line1\nline2"));
    }

    @Test
    void escape_shouldKeepPlainValueUnchanged() {
        assertEquals("plain-123", CsvUtil.escape("plain-123"));
    }

    @Test
    void fmt_shouldReturnEmptyForNullTime() {
        assertEquals("", CsvUtil.fmt(null));
    }

    @Test
    void fmt_shouldFormatAsReadablePattern() {
        assertEquals("2026-08-29 12:30:45",
                CsvUtil.fmt(LocalDateTime.of(2026, 8, 29, 12, 30, 45)));
    }

    @Test
    void line_shouldProduceHeaderAlignedRow() {
        ApiCallLog log = new ApiCallLog();
        log.setId(7L);
        log.setAppName("订单,服务"); // 含逗号 → 触发转义
        log.setInterfacePath("/api/order");
        log.setRequestMethod("POST");
        log.setRequestTime(LocalDateTime.of(2026, 8, 29, 10, 0, 0));
        log.setResponseStatus(200);
        log.setCostTime(12);
        log.setClientIp("10.0.0.1");
        log.setIsRateLimited(false);
        log.setIsBlocked(true);
        log.setBlockReason("权限越界");
        log.setCreatedAt(LocalDateTime.of(2026, 8, 29, 10, 0, 1));

        String line = CsvUtil.line(log);

        // 与表头 12 列一一对应；含逗号字段按 RFC 4180 转义
        assertEquals("7,\"订单,服务\",/api/order,POST,2026-08-29 10:00:00,200,12,10.0.0.1,false,true,权限越界,2026-08-29 10:00:01\n",
                line);
    }
}
