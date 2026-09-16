package com.gatekeeper.exception;

import com.gatekeeper.common.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局异常处理器的「错误码归属」单测（T18 补漏）。
 *
 * <p>本类锁的是一条容易退化的边界：<b>客户端错误不许伪装成服务端故障</b>。
 * 背景是 T18 导入链路实测：请求体里 {@code {"groupId": [12]}}（数组塞进 Long 字段）
 * 触发 Jackson {@code MismatchedInputException}，因当时没有
 * {@code HttpMessageNotReadableException} 专属处理器，异常一路落到兜底
 * {@code Exception} 分支 ⇒ 客户端收到 <b>500「系统繁忙，请稍后重试」</b>。
 * 这会让调用方把「我传错了」误判为「服务挂了」，并制造无意义的故障告警。</p>
 *
 * <p>故这里把 400/500 的分工钉死，并额外守住「对外不泄漏内部类名」这条安全口径。</p>
 */
@DisplayName("全局异常处理器：400 与 500 的归属边界")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * 构造一个"像真的一样"的 Jackson 反序列化失败异常：真实报文里含有
     * 内部 DTO 类名与字段引用链，正好用来同时验证「码为 400」与「不外泄」。
     */
    private HttpMessageNotReadableException unreadableBody() {
        return new HttpMessageNotReadableException(
                "JSON parse error: Cannot deserialize value of type `java.lang.Long` from Array value"
                        + " (token `JsonToken.START_ARRAY`); nested exception is"
                        + " com.fasterxml.jackson.databind.exc.MismatchedInputException: Cannot deserialize"
                        + " value of type `java.lang.Long` from Array value (through reference chain:"
                        + " com.gatekeeper.dto.InterfaceImportRequest[\"groupId\"])");
    }

    @Test
    @DisplayName("请求体类型不匹配 ⇒ 400，而不是兜底 500「系统繁忙」")
    void unreadableBody_isClientError_notCatchAllFiveHundred() {
        Result<Void> r = handler.handleMessageNotReadable(unreadableBody());

        assertEquals(400, r.getCode(), "请求体格式错误属客户端错误，必须是 400");
        assertFalse(r.getMessage().contains("系统繁忙"),
                "不得落到兜底 500 的通用提示，否则调用方分不清是自己传错还是服务端故障");
    }

    @Test
    @DisplayName("400 提示不泄漏内部 DTO 类名与引用链")
    void unreadableBody_doesNotLeakInternalClassNames() {
        String msg = handler.handleMessageNotReadable(unreadableBody()).getMessage();

        assertFalse(msg.contains("InterfaceImportRequest"), "提示里不得出现内部 DTO 类名");
        assertFalse(msg.contains("gatekeeper"), "提示里不得出现内部包名");
        assertFalse(msg.contains("jackson"), "提示里不得出现内部框架/异常类名");
        assertFalse(msg.contains("groupId"), "提示里不得回显具体字段引用链");
        assertTrue(msg.contains("JSON"), "但应当给调用方可操作的修复方向");
    }

    @Test
    @DisplayName("GatewayException 保留自身业务码与 HTTP 状态（400/404/429/502）")
    void gatewayException_keepsItsOwnBusinessCode() {
        int[] codes = {400, 401, 403, 404, 429, 502, 504};
        for (int code : codes) {
            ResponseEntity<Result<Void>> resp =
                    handler.handleGatewayException(new GatewayException(code, "caso " + code));

            assertEquals(code, resp.getStatusCodeValue(),
                    "HTTP 状态码应等于业务码，code=" + code);
            assertNotNull(resp.getBody(), "响应体不应为空，code=" + code);
            assertEquals(code, resp.getBody().getCode(), "响应体 code 应为 " + code);
            assertEquals("caso " + code, resp.getBody().getMessage(), "业务提示应原样透出");
        }
    }

    @Test
    @DisplayName("非业务码（如 999）降级为 500，而不是原样回显非法状态码")
    void gatewayException_unknownCode_fallsBackToFiveHundred() {
        ResponseEntity<Result<Void>> resp =
                handler.handleGatewayException(new GatewayException(999, "非法业务码"));

        assertEquals(500, resp.getStatusCodeValue(), "无法解析的状态码应降级为 500");
        assertNotNull(resp.getBody());
        assertEquals(999, resp.getBody().getCode(), "但响应体仍保留原业务码供排查");
    }

    @Test
    @DisplayName("未预期异常仍是兜底 500，且不外泄异常细节")
    void unexpectedException_fallsBackToGenericFiveHundred() {
        Result<Void> r = handler.handleException(
                new IllegalStateException("连接 MySQL 失败: jdbc:mysql://192.168.132.143:13306/gatekeeper"));

        assertEquals(500, r.getCode());
        assertEquals("系统繁忙，请稍后重试", r.getMessage(), "兜底提示应固定为通用文案");
        assertFalse(r.getMessage().contains("MySQL"), "不得外泄数据库连接串等内部细节");
    }
}
