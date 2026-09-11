package com.gatekeeper.util;

import com.gatekeeper.entity.ApiCallLog;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * CSV 输出工具 — 调用日志导出专用
 *
 * <p>从 CallLogController 中抽取（原为私有方法），供异步导出执行器与同步场景复用，
 * 统一处理：字段转义（逗号/双引号/换行）、时间格式化、表头与单行拼接。</p>
 *
 * <p>转义规则（RFC 4180）：字段值含逗号、双引号、换行时整体用双引号包裹，
 * 内部双引号翻倍。Excel 打开中文需在文件首写 UTF-8 BOM（见 {@link #BOM}）。</p>
 */
public final class CsvUtil {

    /** UTF-8 BOM 字符，写入文件开头保证 Excel 正确识别中文 */
    public static final char BOM = '\uFEFF';

    /** 调用日志 CSV 表头（12 列，与 {@link #line(ApiCallLog)} 一一对应） */
    public static final String HEADER =
            "日志ID,应用名称,接口路径,请求方法,请求时间,响应状态,耗时(ms),客户端IP,是否限流,是否拦截,拦截原因,创建时间";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CsvUtil() {
    }

    /**
     * 将单条调用日志拼接为一行 CSV（以换行符结尾）
     *
     * @param log 调用日志
     * @return 行内容（含 \n）
     */
    public static String line(ApiCallLog log) {
        return String.join(",",
                        escape(log.getId()),
                        escape(log.getAppName()),
                        escape(log.getInterfacePath()),
                        escape(log.getRequestMethod()),
                        escape(fmt(log.getRequestTime())),
                        escape(log.getResponseStatus()),
                        escape(log.getCostTime()),
                        escape(log.getClientIp()),
                        escape(log.getIsRateLimited()),
                        escape(log.getIsBlocked()),
                        escape(log.getBlockReason()),
                        escape(fmt(log.getCreatedAt())))
                + "\n";
    }

    /** 字段 CSV 转义：含逗号/引号/换行时整体用双引号包裹，内部双引号翻倍 */
    public static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** 时间格式化（yyyy-MM-dd HH:mm:ss），null 返回空串 */
    public static String fmt(LocalDateTime time) {
        return time == null ? "" : time.format(TIME_FORMAT);
    }
}
