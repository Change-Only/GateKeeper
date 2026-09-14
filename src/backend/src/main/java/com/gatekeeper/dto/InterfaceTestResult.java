package com.gatekeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口试调测试结果 — T13「新增接口测试功能」
 *
 * <p>把一次试调的全部可解释信息一次性带回来：<b>打了哪个地址、用了哪套生效配置、
 * 送出了哪些头（含签名）、回来的状态码/耗时/报文、以及失败原因</b>。
 * 刻意不做"只回一个成功/失败"的简化 —— 试调的价值恰恰在于出问题时能一眼看出卡在哪一环。</p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@Data
public class InterfaceTestResult {

    /** 是否拿到 2xx/3xx 响应（4xx/5xx 与网络异常均为 false，但 statusCode 仍会带回） */
    private boolean success;

    /** 实际使用的模式：DIRECT / GATEWAY */
    private String mode;

    /** 环境编码 */
    private String envCode;

    /** 请求方法 */
    private String method;

    /** 实际请求的完整地址 */
    private String targetUrl;

    /** HTTP 状态码；网络层失败（连不上/超时）时为 null */
    private Integer statusCode;

    /** 耗时（毫秒） */
    private long costMs;

    /** 响应报文（超过上限会被截断，见 {@link #truncated}） */
    private String responseBody;

    /** 响应报文是否被截断 */
    private boolean truncated;

    /** 送出到对端的请求头（GATEWAY 模式下含 X-App-Key / X-Signature / X-Timestamp / X-Nonce；
     *  X-Signature 值已按"前 8 位 + ***"处理，避免把可用签名留在页面上） */
    private Map<String, String> requestHeaders = new LinkedHashMap<>();

    /** 失败原因（网络异常 / 目标地址非法）；statusCode 为 4xx/5xx 时此处为空，看 responseBody */
    private String error;

    // ==================== 走网关模式：用了哪个应用 ====================
    /** 使用的应用 ID（仅 GATEWAY） */
    private Long appId;

    /** 使用的应用名称（仅 GATEWAY） */
    private String appName;

    // ==================== 生效环境配置回显 ====================
    /** 生效配置来源：INTERFACE / GROUP / DEFAULT */
    private String sourceType;

    /** 来源链路（如「配网 / 核心指标」） */
    private String sourcePath;

    /** 生效的连接超时(ms) */
    private Integer connectTimeout;

    /** 生效的读取超时(ms) */
    private Integer readTimeout;

    /** 生效的重试次数 */
    private Integer retryCount;

    /** 本次是否命中 Mock 短路（网关模式下由网关判定并体现为返回体；直连模式下 true 表示
     *  该环境已开启 Mock，网关路径不会真正转发后端） */
    private boolean mock;

    // ==================== 过程说明 ====================
    /** 过程说明：自动选应用的理由、Mock 提示、路径候选等。全部原样展示，便于排障 */
    private List<String> notes = new ArrayList<>();

    /** 追加一条说明 */
    public void note(String text) {
        if (text != null && !text.isEmpty()) {
            this.notes.add(text);
        }
    }
}
