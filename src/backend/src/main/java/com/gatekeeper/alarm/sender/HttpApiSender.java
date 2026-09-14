package com.gatekeeper.alarm.sender;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.gatekeeper.entity.NotifyChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP「自定义接口」渠道发送器 — T12 新增（T09 渠道体系的第 3 个 SPI 实现）
 *
 * <p>与 {@link WebhookSender} 的区别：WECOM/DINGTALK/WEBHOOK 三个类型共用<b>固定</b>的
 * 企微文本报文 {@code {"msgtype":"text","text":{"content":...}}}，只适合企微/钉钉系接收端；
 * 本类面向<b>任意第三方接口</b>——URL / 请求方法 / 请求头 / 请求体模板 / 成功判定全部可配置，
 * 告警触发时可把报文按对端约定的形状投递过去。</p>
 *
 * <h3>channel_config JSON Schema（T12 契约 v1，与前端 {@code SysNotify.vue} 的 CONFIG_SCHEMA.HTTP 一致）</h3>
 * <pre>{@code
 * {
 *   "url":            "https://ops.example.com/api/v1/alarm",  // 必填；支持占位符
 *   "method":         "POST",                    // POST(默认) / PUT / GET
 *   "contentType":    "application/json",        // application/json(默认) / application/x-www-form-urlencoded / text/plain
 *   "headers":        "{\"Authorization\":\"Bearer ${password}\"}",  // 可选；JSON 对象串，值支持占位符
 *   "bodyTemplate":   "{\"title\":\"${title}\",\"content\":\"${content}\"}", // 可选；空则用默认报文
 *   "timeoutMs":      5000,                      // 可选；默认 5000，钳制在 [500, 60000]
 *   "successJsonPath":"code",                    // 可选；响应 JSON 中判成功的字段路径（如 code / data.ok）
 *   "successJsonValue":"0",                      // 可选；上述字段等于此值即成功，默认 "0"
 *   "password":       "enc:BASE64密文"            // 可选；唯一加密字段，供 ${password} 占位（服务层加解密）
 * }
 * }</pre>
 *
 * <h3>占位符</h3>
 * <p>可用于 {@code url} / {@code headers} 的值 / {@code bodyTemplate}：
 * {@code ${title}}（标题）、{@code ${content}}（正文）、{@code ${time}}（yyyy-MM-dd HH:mm:ss）、
 * {@code ${channelName}}、{@code ${channelId}}、{@code ${password}}（已解密）。</p>
 * <p><strong>转义规则</strong>：当 {@code contentType=application/json} 且目标是 {@code bodyTemplate} 时，
 * 替换进去的值会做 JSON 字符串转义（{@code "} → {@code \"}、换行 → {@code \n} 等），
 * 因此 {@code {"msg":"${content}"}} 这类模板即使正文含引号/换行也不会把 JSON 打坏；
 * URL 与请求头一律原样替换（用它们时请自行 {@code URLEncoder} 或避免特殊字符）。</p>
 *
 * <h3>失败判定（与 T09 铁律一致）</h3>
 * <ul>
 *   <li>channel 为 null / channelConfig 非 JSON / url 缺失 / 请求方法不支持 → false</li>
 *   <li><strong>停用渠道（status=0）→ false</strong>（未发送 ≠ 成功，对齐 WebhookSender 的 R4 语义）</li>
 *   <li>HTTP 非 2xx → RestTemplate 抛 {@code RestClientException} → catch → false</li>
 *   <li>配置了 {@code successJsonPath} 时：响应体非 JSON / 路径取不到值 / 值 ≠ {@code successJsonValue} → false</li>
 *   <li>未配置 {@code successJsonPath} 时：2xx 即成功（对端无约定响应体结构时不凭空制造失败）</li>
 *   <li><strong>永不抛异常</strong>（SPI 铁律，见 {@link ChannelSender#send}）</li>
 * </ul>
 *
 * <h3>超时</h3>
 * <p>默认构造用 {@link SimpleClientHttpRequestFactory} 建 5s 连接/读取超时的 RestTemplate，
 * 防外部接口黑洞拖垮 10s 周期的告警评估线程（JDK {@code HttpURLConnection} 默认<b>无超时</b>，
 * 这是旧 {@code WebhookSender} 留下的隐患面）。渠道配置 {@code timeoutMs} 生效时按值缓存实例。</p>
 *
 * <p>可测试性：注入 {@link RestTemplate} 的构造（对齐 {@code NotifySenderTest} 注入 mock 的范式），
 * 该构造下固定使用注入实例、不启用按渠道超时缓存。</p>
 *
 * @author GateKeeper
 * @since T12 (APIM V2)
 */
@Slf4j
@Component
public class HttpApiSender implements ChannelSender {

    /** 支持的渠道类型（单一来源，NotifySender 按此路由） */
    private static final String[] TYPES = {"HTTP"};

    /** 允许的请求方法（JDK HttpURLConnection 不支持 PATCH，故不纳入） */
    private static final String[] ALLOWED_METHODS = {"GET", "POST", "PUT"};

    /** 默认超时（毫秒） */
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    /** 超时下限/上限（下限防误配成 1ms，上限防拖垮 10s 评估周期） */
    private static final int MIN_TIMEOUT_MS = 500;
    private static final int MAX_TIMEOUT_MS = 60000;

    /** 时间占位符格式 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 响应体日志截断长度 */
    private static final int RESP_LOG_LIMIT = 500;

    /** 默认模板/生产模板（或测试注入的 mock） */
    private final RestTemplate defaultTemplate;

    /** 是否允许按渠道 {@code timeoutMs} 换用带自定义超时的实例（生产构造才 true） */
    private final boolean allowCustomTimeout;

    /** 自定义超时实例缓存：timeoutMs → RestTemplate（防每次发送重建请求工厂） */
    private final Map<Integer, RestTemplate> timeoutTemplates = new ConcurrentHashMap<>();

    /**
     * 默认构造（生产环境 Spring 实例化）：内置 5s 超时的 RestTemplate。
     */
    public HttpApiSender() {
        this(createRestTemplate(DEFAULT_TIMEOUT_MS), true);
    }

    /**
     * 注入 RestTemplate 的构造（主要用于单元测试注入 mock；不启用按渠道超时缓存）。
     *
     * @param restTemplate RestTemplate 实例
     */
    public HttpApiSender(RestTemplate restTemplate) {
        this(restTemplate, false);
    }

    private HttpApiSender(RestTemplate template, boolean allowCustomTimeout) {
        this.defaultTemplate = template;
        this.allowCustomTimeout = allowCustomTimeout;
    }

    @Override
    public String[] supportTypes() {
        // 新增渠道类型必须同时改前端 utils/enum.js 与 SysNotify.vue 的 CONFIG_SCHEMA，
        // 否则前端配了却不发（落回 NotifySender 的桩发返回 true）＝ 假成功，正是本项目要消灭的缺陷类。
        return TYPES.clone();
    }

    @Override
    public boolean send(NotifyChannel channel, String title, String content) {
        if (channel == null) {
            return false;
        }
        try {
            JSONObject cfg = parseConfig(channel.getChannelConfig());
            if (cfg == null) {
                log.warn("channel {} config is empty or invalid JSON, skip", channel.getId());
                return false;
            }
            // url 优先，回退 webhook（与前端 CONFIG_SCHEMA.HTTP.aliases 对应的读侧别名）
            String url = cfg.getStr("url", cfg.getStr("webhook"));
            if (!StringUtils.hasText(url)) {
                log.warn("channel {} missing url, skip", channel.getId());
                return false;
            }
            // 停用渠道 = 未发送，返回 false（对齐 WebhookSender R4 语义）
            if (channel.getStatus() != null && channel.getStatus() == 0) {
                log.warn("channel {} is disabled, skip send", channel.getId());
                return false;
            }
            HttpMethod method = resolveMethod(cfg.getStr("method"));
            if (method == null) {
                log.warn("channel {} unsupported method={} (allowed: {}), skip",
                        channel.getId(), cfg.getStr("method"), String.join("/", ALLOWED_METHODS));
                return false;
            }
            MediaType mediaType = resolveMediaType(cfg.getStr("contentType"));
            Map<String, String> ph = buildPlaceholders(channel, title, content, cfg.getStr("password"));

            String targetUrl = render(url, ph, false);
            HttpHeaders headers = buildHeaders(cfg.getStr("headers"), mediaType, ph);
            HttpEntity<String> entity = buildEntity(method, headers, cfg.getStr("bodyTemplate"), mediaType, ph,
                    channel, title, content);

            String resp = templateFor(cfg.getInt("timeoutMs"))
                    .exchange(targetUrl, method, entity, String.class).getBody();
            log.info("channel {} HTTP api sent: method={} url={} resp={}",
                    channel.getId(), method, targetUrl, truncate(resp));
            return isSuccess(resp, cfg);
        } catch (Exception e) {
            // SPI 铁律：任何异常 catch → false，绝不抛给调用方（告警发送失败不得阻断评估主流程）
            log.error("channel {} HTTP api send failed: {}", channel.getId(), e.getMessage());
            return false;
        }
    }

    // =====================================================================
    // 请求装配
    // =====================================================================

    /**
     * 构造请求体实体的取值域。
     *
     * <p>GET 不带请求体（JDK {@code HttpURLConnection} 不允许 GET 写输出流）：参数请写在 url 的
     * 占位符里或对端约定的查询串上；其余方法按 {@code bodyTemplate}（空则用默认报文）产生请求体。</p>
     */
    private HttpEntity<String> buildEntity(HttpMethod method, HttpHeaders headers, String bodyTemplate,
                                           MediaType mediaType, Map<String, String> ph,
                                           NotifyChannel channel, String title, String content) {
        if (HttpMethod.GET.equals(method)) {
            return new HttpEntity<>(headers);
        }
        String body;
        if (StringUtils.hasText(bodyTemplate)) {
            // JSON 请求体：替换值做 JSON 转义，避免正文含引号/换行把模板打坏
            // （按 subtype 判定而非 equals：容忍 application/problem+json 一类变体）
            body = render(bodyTemplate, ph, mediaType.getSubtype().toLowerCase().contains("json"));
        } else {
            body = defaultBody(mediaType, channel, title, content, ph);
        }
        return new HttpEntity<>(body, headers);
    }

    /**
     * 默认请求体（未配置 {@code bodyTemplate} 时）：
     * JSON → {@code {"source","channel","title","content","time"}}；表单 → 同名键值对；文本 → 标题换行正文。
     */
    private String defaultBody(MediaType mediaType, NotifyChannel channel, String title,
                               String content, Map<String, String> ph) {
        String sub = mediaType.getSubtype();
        String channelName = ph.get("channelName");
        String time = ph.get("time");
        if (sub.contains("json")) {
            JSONObject o = new JSONObject();
            o.set("source", "GateKeeper");
            o.set("channel", channelName);
            o.set("title", title == null ? "" : title);
            o.set("content", content == null ? "" : content);
            o.set("time", time);
            return o.toString();
        }
        if (sub.contains("x-www-form-urlencoded")) {
            StringBuilder sb = new StringBuilder();
            appendForm(sb, "source", "GateKeeper");
            appendForm(sb, "channel", channelName);
            appendForm(sb, "title", title == null ? "" : title);
            appendForm(sb, "content", content == null ? "" : content);
            appendForm(sb, "time", time);
            return sb.toString();
        }
        // text/plain 及其它：标题 + 换行 + 正文
        return (title == null ? "" : title) + "\n" + (content == null ? "" : content);
    }

    private void appendForm(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) {
            sb.append('&');
        }
        sb.append(key).append('=').append(urlEncode(value));
    }

    /**
     * 组装请求头：先落 Content-Type，再叠加用户 {@code headers}（同名时用户值生效）。
     * 请求头值做 <b>CRLF 清洗</b>（防头注入），并支持占位符。
     */
    private HttpHeaders buildHeaders(String headersRaw, MediaType mediaType, Map<String, String> ph) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        if (!StringUtils.hasText(headersRaw)) {
            return headers;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(headersRaw);
            for (String name : obj.keySet()) {
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                String value = render(String.valueOf(obj.getStr(name, "")), ph, false);
                headers.set(name.trim(), stripCrlf(value));
            }
        } catch (Exception e) {
            // 请求头非法 JSON：不改写头部（Content-Type 已就位），但仍按配置尽力发送
            log.warn("channel headers is not a JSON object, ignored: {}", e.getMessage());
        }
        return headers;
    }

    /**
     * 解析请求方法（大写、去空白）；不支持时返回 {@code null}（由调用方 warn + false）。
     */
    private HttpMethod resolveMethod(String raw) {
        String m = StringUtils.hasText(raw) ? raw.trim().toUpperCase() : "POST";
        for (String allowed : ALLOWED_METHODS) {
            if (allowed.equals(m)) {
                return HttpMethod.valueOf(m);
            }
        }
        return null;
    }

    /**
     * 解析 Content-Type（缺省 application/json；非法值回退 application/json 并 warn，不因此判失败）。
     */
    private MediaType resolveMediaType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return MediaType.APPLICATION_JSON;
        }
        try {
            return MediaType.parseMediaType(raw.trim());
        } catch (Exception e) {
            log.warn("invalid contentType '{}', fallback to application/json", raw);
            return MediaType.APPLICATION_JSON;
        }
    }

    /**
     * 按渠道 {@code timeoutMs} 取 RestTemplate（生产构造才允许换实例；测试注入恒用注入实例）。
     */
    private RestTemplate templateFor(Integer timeoutMs) {
        if (!allowCustomTimeout || timeoutMs == null || timeoutMs <= 0) {
            return defaultTemplate;
        }
        int ms = Math.max(MIN_TIMEOUT_MS, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        if (ms == DEFAULT_TIMEOUT_MS) {
            return defaultTemplate;
        }
        return timeoutTemplates.computeIfAbsent(ms, HttpApiSender::createRestTemplate);
    }

    /**
     * 构造带连接/读取超时的 RestTemplate（默认 JDK HttpURLConnection，无额外依赖）。
     */
    static RestTemplate createRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    // =====================================================================
    // 占位符与渲染
    // =====================================================================

    /**
     * 占位符取值表（键不含 {@code ${}} 包裹，渲染时统一包裹，防"值里含占位符"被二次替换）。
     */
    private Map<String, String> buildPlaceholders(NotifyChannel channel, String title,
                                                 String content, String password) {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("title", title == null ? "" : title);
        ph.put("content", content == null ? "" : content);
        ph.put("time", LocalDateTime.now().format(TIME_FMT));
        ph.put("channelName", channel.getChannelName() == null ? "" : channel.getChannelName());
        ph.put("channelId", channel.getId() == null ? "" : String.valueOf(channel.getId()));
        ph.put("password", password == null ? "" : password);
        return ph;
    }

    /**
     * 替换占位符。
     *
     * <p>逐 key 用 {@link String#replace(CharSequence, CharSequence)}（字面量，非正则）替换；
     * 替换结果<b>不再参与后续 key 的替换</b>——每轮都在上一轮产物上做字面替换虽会二次扫描，
     * 但占位符键彼此不同名且取值不含 {@code ${}}（配置里的 password 由运维填写），实测足够；
     * 若将来支持"取值里带占位符"需改为单遍扫描。</p>
     *
     * @param jsonEscape 是否为 JSON 字符串上下文（true 时对替换值做 JSON 转义）
     */
    private String render(String template, Map<String, String> ph, boolean jsonEscape) {
        if (template == null) {
            return "";
        }
        String out = template;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            String value = jsonEscape ? jsonEscape(e.getValue()) : e.getValue();
            out = out.replace("${" + e.getKey() + "}", value);
        }
        return out;
    }

    /**
     * JSON 字符串转义（不含首尾引号）：模板里写成 {@code "${content}"} 即可安全承载任意正文。
     */
    static String jsonEscape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private String urlEncode(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return v == null ? "" : v;
        }
    }

    /** 请求头值 CRLF 清洗（头注入防线） */
    private String stripCrlf(String v) {
        return v == null ? "" : v.replace("\r", "").replace("\n", "");
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= RESP_LOG_LIMIT ? s : s.substring(0, RESP_LOG_LIMIT) + "...";
    }

    // =====================================================================
    // 成功判定
    // =====================================================================

    /**
     * 成功判定：未配 {@code successJsonPath} → 2xx 即成功；
     * 配了则需响应为 JSON、路径取值非 null 且等于 {@code successJsonValue}（默认 {@code "0"}）。
     *
     * <p>与 WECOM/DINGTALK 固定判 {@code errcode} 的做法不同：外部接口的响应结构不可预知，
     * 故由渠道配置显式声明判定路径，避免"HTTP 200 但业务失败"被记为成功（R5 同类缺陷）。</p>
     */
    private boolean isSuccess(String resp, JSONObject cfg) {
        String path = cfg.getStr("successJsonPath");
        if (!StringUtils.hasText(path)) {
            return true;
        }
        if (!StringUtils.hasText(resp)) {
            log.warn("successJsonPath={} configured but response is empty, treat as failure", path);
            return false;
        }
        Object actual;
        try {
            actual = getByPath(JSONUtil.parseObj(resp), path.trim());
        } catch (Exception e) {
            log.warn("response is not JSON while successJsonPath={} configured, treat as failure: {}",
                    path, truncate(resp));
            return false;
        }
        if (actual == null) {
            log.warn("successJsonPath={} not found in response, treat as failure: {}", path, truncate(resp));
            return false;
        }
        String expected = cfg.getStr("successJsonValue", "0");
        String actualStr = String.valueOf(actual);
        if (!expected.equals(actualStr)) {
            log.warn("HTTP api rejected: {}={} (expected {}), resp={}", path, actualStr, expected, truncate(resp));
            return false;
        }
        return true;
    }

    /**
     * 点分路径取值（{@code code} / {@code data.ok} / {@code list.0.id}），取不到返回 null。
     *
     * <p>自实现而非用 {@code JSONUtil.getByPath}：行为可控、异常面小，且便于单测覆盖
     * 「路径不存在」「数组下标越界」等失败分支。</p>
     */
    private Object getByPath(JSONObject root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (cur instanceof JSONObject) {
                cur = ((JSONObject) cur).get(seg);
            } else if (cur instanceof JSONArray) {
                JSONArray arr = (JSONArray) cur;
                int idx;
                try {
                    idx = Integer.parseInt(seg);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (idx < 0 || idx >= arr.size()) {
                    return null;
                }
                cur = arr.get(idx);
            } else {
                return null;
            }
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    /**
     * 解析渠道配置 JSON（容错：非法 JSON 返回 null）。与 WebhookSender/EmailSmtpSender 同款。
     */
    private JSONObject parseConfig(String config) {
        if (!StringUtils.hasText(config)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(config);
        } catch (Exception e) {
            log.warn("parse channelConfig failed: {}", e.getMessage());
            return null;
        }
    }
}
