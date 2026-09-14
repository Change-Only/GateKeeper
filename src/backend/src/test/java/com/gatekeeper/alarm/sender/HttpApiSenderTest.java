package com.gatekeeper.alarm.sender;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.gatekeeper.entity.NotifyChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HttpApiSender 单测 — T12 自定义外部接口渠道
 *
 * <p>覆盖三类语义（对齐 T09 方法纪律「能失败的用例优先于接口 200」）：</p>
 * <ol>
 *   <li><strong>真发</strong>：URL/方法/Content-Type/请求体/请求头按配置落到 RestTemplate 调用上</li>
 *   <li><strong>失败判定</strong>：successJsonPath 不匹配 / 响应非 JSON / 停用渠道 / 缺 url /
 *       方法不支持 / 异常 —— 一律 false（其中 successJsonPath 用例是本类的「能失败锚点」：
 *       不做判定实现时会返回 true，即「HTTP 200 但业务失败被记为成功」的 R5 同类缺陷）</li>
 *   <li><strong>不变量</strong>：永不抛异常（SPI 铁律）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpApiSender 自定义接口渠道")
class HttpApiSenderTest {

    private static final String URL = "https://ops.example.com/api/v1/alarm";

    @Mock
    private RestTemplate restTemplate;

    private HttpApiSender sender;

    @BeforeEach
    void setUp() {
        // 注入 mock 的构造：固定使用注入实例（不启用按渠道超时缓存）
        sender = new HttpApiSender(restTemplate);
    }

    private NotifyChannel channel(String config, Integer status) {
        NotifyChannel c = new NotifyChannel();
        c.setId(9L);
        c.setChannelName("运维告警接口");
        c.setChannelType("HTTP");
        c.setChannelConfig(config);
        c.setStatus(status);
        return c;
    }

    private void stubOk(String body) {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    @SuppressWarnings("unchecked")
    private HttpEntity<String> captureEntity() {
        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URL), any(HttpMethod.class), captor.capture(), eq(String.class));
        return captor.getValue();
    }

    // ---------------------------------------------------------------- 真发

    @Test
    @DisplayName("POST + 默认报文：发起 exchange，默认 JSON 体含 source/channel/title/content/time")
    void postDefaultJsonBody() {
        stubOk("ok");
        boolean ok = sender.send(channel("{\"url\":\"" + URL + "\"}", 1), "标题", "正文");
        assertTrue(ok);
        HttpEntity<String> entity = captureEntity();
        JSONObject body = JSONUtil.parseObj(entity.getBody());
        assertEquals("GateKeeper", body.getStr("source"));
        assertEquals("运维告警接口", body.getStr("channel"));
        assertEquals("标题", body.getStr("title"));
        assertEquals("正文", body.getStr("content"));
        assertNotNull(body.getStr("time"));
        assertTrue(String.valueOf(entity.getHeaders().getContentType()).startsWith("application/json"),
                "默认 Content-Type 应为 application/json");
    }

    @Test
    @DisplayName("webhook 作为 url 的读侧别名同样可用（与前端 CONFIG_SCHEMA.HTTP.aliases 对应）")
    void webhookAliasForUrl() {
        stubOk("ok");
        assertTrue(sender.send(channel("{\"webhook\":\"" + URL + "\"}", 1), "t", "c"));
        captureEntity();
    }

    @Test
    @DisplayName("请求体模板：占位符替换 + JSON 转义（正文含引号/换行也不打坏 JSON）")
    void bodyTemplateRenderedWithJsonEscape() {
        stubOk("ok");
        String tpl = "{\"t\":\"${title}\",\"c\":\"${content}\"}";
        // 用 JSONObject 组装配置串，避免手写嵌套引号转义出错
        JSONObject cfg = new JSONObject();
        cfg.set("url", URL);
        cfg.set("bodyTemplate", tpl);
        String messy = "双引号\" 换行\n结束";
        assertTrue(sender.send(channel(cfg.toString(), 1), "t", messy));

        JSONObject parsed = JSONUtil.parseObj(captureEntity().getBody());
        assertEquals("t", parsed.getStr("t"));
        assertEquals(messy, parsed.getStr("c"), "转义后应能原样还原正文（含引号与换行）");
    }

    @Test
    @DisplayName("请求头：headers 里的 ${password} 用渠道密钥替换")
    void headersRenderedWithPassword() {
        stubOk("ok");
        JSONObject headers = new JSONObject();
        headers.set("X-Token", "Bearer ${password}");
        JSONObject cfg = new JSONObject();
        cfg.set("url", URL);
        cfg.set("headers", headers.toString());
        cfg.set("password", "s3cret");
        assertTrue(sender.send(channel(cfg.toString(), 1), "t", "c"));
        assertEquals("Bearer s3cret", captureEntity().getHeaders().getFirst("X-Token"));
    }

    @Test
    @DisplayName("GET 不带请求体（JDK HttpURLConnection 限制），参数走 URL 占位符")
    void getSendsNoBody() {
        stubOk("ok");
        String cfg = "{\"url\":\"" + URL + "?msg=${title}\",\"method\":\"GET\",\"bodyTemplate\":\"ignored\"}";
        assertTrue(sender.send(channel(cfg, 1), "hi", "c"));

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URL + "?msg=hi"), eq(HttpMethod.GET), captor.capture(), eq(String.class));
        assertNull(captor.getValue().getBody(), "GET 不应带请求体");
    }

    @Test
    @DisplayName("PUT + 表单格式：默认报文为 key=value 且已 URL 编码")
    void putFormBody() {
        stubOk("ok");
        String cfg = "{\"url\":\"" + URL + "\",\"method\":\"PUT\","
                + "\"contentType\":\"application/x-www-form-urlencoded\"}";
        assertTrue(sender.send(channel(cfg, 1), "标题", "a&b"));
        String body = captureEntity().getBody();
        assertTrue(body.startsWith("source=GateKeeper"), "表单体应为 key=value 串：" + body);
        assertTrue(body.contains("title=%E6%A0%87%E9%A2%98"), "非 ASCII 应被 URL 编码：" + body);
        assertTrue(body.contains("content=a%26b"), "保留字符应被 URL 编码：" + body);
    }

    @Test
    @DisplayName("注入 RestTemplate 的构造下，渠道 timeoutMs 不换实例（仍走注入的 mock）")
    void customTimeoutDoesNotSwapTemplate() {
        stubOk("ok");
        String cfg = "{\"url\":\"" + URL + "\",\"timeoutMs\":1000}";
        assertTrue(sender.send(channel(cfg, 1), "t", "c"));
        captureEntity();
    }

    @Test
    @DisplayName("默认构造产出带连接/读取超时的 RestTemplate（防外部接口黑洞拖垮告警线程）")
    void defaultConstructorBuildsTimeoutTemplate() {
        assertNotNull(HttpApiSender.createRestTemplate(1200));
    }

    // -------------------------------------------- 失败判定（含「能失败锚点」）

    @Test
    @DisplayName("能失败锚点：successJsonPath=code 而响应 code=500 → false（未实现判定时会返回 true）")
    void successJsonPathMismatchFails() {
        stubOk("{\"code\":500,\"msg\":\"rejected\"}");
        String cfg = "{\"url\":\"" + URL + "\",\"successJsonPath\":\"code\"}";
        assertFalse(sender.send(channel(cfg, 1), "t", "c"),
                "对端返回业务失败（code=500）却判成功 —— 与 WECOM errcode≠0 属同类语义缺陷");
    }

    @Test
    @DisplayName("successJsonPath=code 且响应 code=0 → true")
    void successJsonPathMatched() {
        stubOk("{\"code\":0,\"msg\":\"ok\"}");
        String cfg = "{\"url\":\"" + URL + "\",\"successJsonPath\":\"code\"}";
        assertTrue(sender.send(channel(cfg, 1), "t", "c"));
    }

    @Test
    @DisplayName("successJsonValue 可自定义（如 code=200）")
    void successJsonValueCustom() {
        stubOk("{\"code\":200}");
        String cfg = "{\"url\":\"" + URL + "\",\"successJsonPath\":\"code\",\"successJsonValue\":\"200\"}";
        assertTrue(sender.send(channel(cfg, 1), "t", "c"));
    }

    @Test
    @DisplayName("successJsonPath 支持点分路径 data.ok")
    void successJsonPathNested() {
        stubOk("{\"data\":{\"ok\":true}}");
        String cfg = "{\"url\":\"" + URL + "\",\"successJsonPath\":\"data.ok\",\"successJsonValue\":\"true\"}";
        assertTrue(sender.send(channel(cfg, 1), "t", "c"));
    }

    @Test
    @DisplayName("successJsonPath 路径不存在 → false")
    void successJsonPathMissingFails() {
        stubOk("{\"msg\":\"ok\"}");
        String cfg = "{\"url\":\"" + URL + "\",\"successJsonPath\":\"code\"}";
        assertFalse(sender.send(channel(cfg, 1), "t", "c"));
    }

    @Test
    @DisplayName("配了 successJsonPath 但响应非 JSON → false（不可验证即失败）")
    void successJsonPathNonJsonFails() {
        stubOk("OK");
        String cfg = "{\"url\":\"" + URL + "\",\"successJsonPath\":\"code\"}";
        assertFalse(sender.send(channel(cfg, 1), "t", "c"));
    }

    @Test
    @DisplayName("配了 successJsonPath 但响应为空 → false")
    void successJsonPathEmptyRespFails() {
        stubOk("");
        String cfg = "{\"url\":\"" + URL + "\",\"successJsonPath\":\"code\"}";
        assertFalse(sender.send(channel(cfg, 1), "t", "c"));
    }

    @Test
    @DisplayName("未配 successJsonPath：2xx 即成功（对端无约定响应体时不凭空制造失败）")
    void noSuccessJsonPathTreatsAnyBodyAsSuccess() {
        stubOk("{\"anything\":1}");
        assertTrue(sender.send(channel("{\"url\":\"" + URL + "\"}", 1), "t", "c"));
    }

    @Test
    @DisplayName("停用渠道（status=0）→ false 且不发起请求（未发送 ≠ 成功）")
    void disabledChannelSkipsWithoutRequest() {
        assertFalse(sender.send(channel("{\"url\":\"" + URL + "\"}", 0), "t", "c"));
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("缺少 url → false 且不发起请求")
    void missingUrlFails() {
        assertFalse(sender.send(channel("{\"method\":\"POST\"}", 1), "t", "c"));
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("channelConfig 非 JSON → false 且不发起请求")
    void invalidConfigFails() {
        assertFalse(sender.send(channel("not-a-json", 1), "t", "c"));
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
    }

    @Test
    @DisplayName("channelConfig 为空 → false")
    void emptyConfigFails() {
        assertFalse(sender.send(channel(null, 1), "t", "c"));
        assertFalse(sender.send(channel("", 1), "t", "c"));
    }

    @Test
    @DisplayName("不支持的请求方法（DELETE）→ false 且不发起请求（不给对端意外副作用）")
    void unsupportedMethodFails() {
        assertFalse(sender.send(channel("{\"url\":\"" + URL + "\",\"method\":\"DELETE\"}", 1), "t", "c"));
        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
    }

    // ---------------------------------------------------------------- 不变量

    @Test
    @DisplayName("对端异常（超时/连接失败/非 2xx）被捕获 → false，永不抛给调用方")
    void transportFailureReturnsFalse() {
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("connect timed out"));
        NotifyChannel c = channel("{\"url\":\"" + URL + "\"}", 1);
        assertDoesNotThrow(() -> sender.send(c, "t", "c"));
        assertFalse(sender.send(c, "t", "c"));
    }

    @Test
    @DisplayName("channel 为 null → false 不抛")
    void nullChannelReturnsFalse() {
        assertFalse(sender.send(null, "t", "c"));
    }

    @Test
    @DisplayName("supportTypes 含 HTTP（注册渠道类型的单一来源）")
    void supportTypesContainsHttp() {
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[]{"HTTP"}, sender.supportTypes());
    }

    @Test
    @DisplayName("jsonEscape：引号/反斜杠/换行/制表/控制字符")
    void jsonEscapeCoversSpecialChars() {
        assertEquals("a\\\"b", HttpApiSender.jsonEscape("a\"b"));
        assertEquals("a\\\\b", HttpApiSender.jsonEscape("a\\b"));
        assertEquals("a\\nb", HttpApiSender.jsonEscape("a\nb"));
        assertEquals("a\\tb", HttpApiSender.jsonEscape("a\tb"));
        assertEquals("\\u0001", HttpApiSender.jsonEscape("\u0001"));
        assertEquals("", HttpApiSender.jsonEscape(null));
    }
}
