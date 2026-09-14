package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.dto.InterfaceTestRequest;
import com.gatekeeper.dto.InterfaceTestResult;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.App;
import com.gatekeeper.entity.AppApiGrant;
import com.gatekeeper.entity.AppApiPermission;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.gateway.EnvConfigResolver;
import com.gatekeeper.gateway.GatewayPaths;
import com.gatekeeper.gateway.dto.EffectiveEnvConfig;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.AppApiGrantMapper;
import com.gatekeeper.mapper.AppApiPermissionMapper;
import com.gatekeeper.mapper.AppMapper;
import com.gatekeeper.service.InterfaceTestService;
import com.gatekeeper.util.CryptoKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 接口试调服务实现 — T13
 *
 * <p><b>为什么不用 {@code RestTemplate}</b>：试调需要"失败也要把过程带回来"，
 * 而 {@code RestTemplate} 遇 4xx/5xx 直接抛异常、遇连接失败抛 {@code ResourceAccessException}，
 * 拿不到响应报文与状态码。Apache HttpClient 可以 `execute` 后照常读实体，48x/50x 也当普通结果处理，
 * 更贴合这个场景（与 {@code UpstreamProber} 的实现风格一致）。</p>
 *
 * <p><b>与生产链路的边界</b>：本服务只读取配置、只发请求，**不写任何业务表**。
 * 唯一副作用是走网关模式会在 {@code api_call_log} 留一条真实调用记录 —— 这是刻意保留的，
 * 因为"这条记录到底记没记"本身就是要验证的东西之一。</p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterfaceTestServiceImpl implements InterfaceTestService {

    /** 响应体回显上限（超出截断） */
    private static final int MAX_BODY_CHARS = 8192;

    /** 读超时下限：走网关时链路比直连长（多了鉴权/限流/权限/日志），给足余量 */
    private static final int GATEWAY_MIN_READ_TIMEOUT_MS = 10000;

    /** 连不上时兜底的连接超时 */
    private static final int FALLBACK_CONNECT_TIMEOUT_MS = 3000;

    /** 连不上时兜底的读超时 */
    private static final int FALLBACK_READ_TIMEOUT_MS = 5000;

    /** 接口超时缺省（与 ForwardHandler 同口径） */
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private final ApiInterfaceMapper apiInterfaceMapper;
    private final AppMapper appMapper;
    private final AppApiGrantMapper appApiGrantMapper;
    private final AppApiPermissionMapper appApiPermissionMapper;
    private final CryptoService cryptoService;
    private final EnvConfigResolver envConfigResolver;

    /** 数据库中 AppSecret 的 AES 加密密钥（与写入端 / 网关校验端一致） */
    @Value("${gatekeeper.crypto.aes-key}")
    private String aesDbKey;

    /**
     * 网关模式回连自身用的基址（如 {@code http://127.0.0.1:8080}）。
     *
     * <p>留空则从当前 Web 请求推导（{@code scheme://host:port}），本机部署通常不必配置；
     * 若网关走独立域名/端口，配 {@code gatekeeper.gateway.self-base-url} 覆盖。</p>
     */
    @Value("${gatekeeper.gateway.self-base-url:}")
    private String selfBaseUrl;

    /** 试调专用 HttpClient：关自动重试（重试语义由网关自身的 retryCount 负责，避免双重重试放大脏数据） */
    private final CloseableHttpClient httpClient = HttpClients.custom()
            .disableAutomaticRetries()
            .build();

    @Override
    public InterfaceTestResult test(Long apiId, InterfaceTestRequest req, HttpServletRequest httpRequest) {
        if (apiId == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        ApiInterface api = apiInterfaceMapper.selectById(apiId);
        if (api == null) {
            throw GatewayException.notFound("接口不存在: id=" + apiId);
        }

        InterfaceTestRequest request = req != null ? req : new InterfaceTestRequest();
        String env = StringUtils.hasText(request.getEnvCode())
                ? request.getEnvCode().trim() : EnvConfigResolver.DEFAULT_ENV;
        String mode = request.normalizedMode();

        // 生效配置：无论哪种模式都要回显（页面上的"生效预览"和这里必须同源）
        EffectiveEnvConfig effective = envConfigResolver.resolve(api, env);

        InterfaceTestResult result = new InterfaceTestResult();
        result.setMode(mode);
        result.setEnvCode(env);
        result.setMethod(api.getRequestMethod());
        result.setSourceType(effective.getSourceType());
        result.setSourcePath(effective.getSourcePath());
        result.setMock(effective.isMockOn());
        if (effective.isMockOn()) {
            result.note("该环境已开启 Mock：走网关时网关会短路返回 Mock 报文，不会真正转发后端。");
        }

        if (InterfaceTestRequest.MODE_GATEWAY.equals(mode)) {
            executeViaGateway(api, request, env, effective, httpRequest, result);
        } else {
            executeDirect(api, request, effective, result);
        }
        return result;
    }

    // =====================================================================
    // 模式一：直连后端
    // =====================================================================

    /**
     * 直连后端：目标 = 生效配置的服务前缀 + 接口URI；无前缀时回退 {@code api_interface.backend_url}。
     *
     * <p>这一步刻意**不看 Mock**：试调直连的意义就是"绕过所有策略，看上游本身通不通"，
     * 若因为 Mock 开着就不发请求，反而丢失了最需要的信息。</p>
     */
    private void executeDirect(ApiInterface api, InterfaceTestRequest req,
                               EffectiveEnvConfig effective, InterfaceTestResult result) {
        String target = EnvConfigResolver.joinUrl(effective.getUpstreamUrl(), api.getInterfacePath());
        if (target == null) {
            target = api.getBackendUrl();
            result.note("该环境没有生效的服务前缀，已回退到接口自身配置的后端地址（api_interface.backend_url）。");
        }
        if (!StringUtils.hasText(target)) {
            result.setError("无法确定目标地址：该环境未配置服务前缀，且接口自身也没有后端地址");
            result.note("请在「接口分组 → 环境配置」里为该分组（或其父级）配置服务前缀。");
            return;
        }
        result.setTargetUrl(target);
        result.setConnectTimeout(pickTimeout(effective.getConnectTimeout(), api.getTimeoutMs(), FALLBACK_CONNECT_TIMEOUT_MS));
        result.setReadTimeout(pickTimeout(effective.getReadTimeout(), api.getTimeoutMs(), FALLBACK_READ_TIMEOUT_MS));
        result.setRetryCount(effective.getRetryCount() == null ? 0 : effective.getRetryCount());

        result.note("直连模式：绕过网关，直接请求上游服务" + describeSource(effective));
        send(api.getRequestMethod(), target, req, result,
                result.getConnectTimeout(), result.getReadTimeout());
    }

    // =====================================================================
    // 模式二：走网关
    // =====================================================================

    /**
     * 走网关：选应用 → 按网关契约签名 → 请求 {@code /gateway/**} → 带回整条链路的结果。
     */
    private void executeViaGateway(ApiInterface api, InterfaceTestRequest req, String env,
                                   EffectiveEnvConfig effective, HttpServletRequest httpRequest,
                                   InterfaceTestResult result) {
        // ① 选应用
        App app = pickApp(api.getId(), env, req.getAppId(), result);
        if (app == null) {
            result.setError("没有可用于试调的应用：请先在「应用管理」创建应用，并确保它对该接口有有效授权");
            return;
        }
        result.setAppId(app.getId());
        result.setAppName(app.getAppName());

        // ② 目标地址：网关统一入口
        String base = resolveSelfBase(httpRequest);
        if (!StringUtils.hasText(base)) {
            result.setError("无法推导网关自身地址：请配置 gatekeeper.gateway.self-base-url");
            return;
        }
        String contextPath = httpRequest != null ? httpRequest.getContextPath() : "";
        String target = GatewayPaths.gatewayEntryUrl(base, contextPath, api.getInterfacePath());
        result.setTargetUrl(target);
        result.note("走网关模式：请求网关统一入口 " + target + "，鉴权/限流/权限/Mock/日志整条链路都会执行。");

        // ③ 签名：SM3(AppKey + AppSecret明文 + Timestamp + Nonce)，与 AppAuthHandler 完全一致
        String plainSecret = decryptSecret(app.getAppSecret());
        if (!StringUtils.hasText(app.getAppKey()) || !StringUtils.hasText(plainSecret)) {
            result.setError("应用凭证不完整（AppKey 或 AppSecret 为空），无法生成网关签名");
            return;
        }
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String signature = cryptoService.digest("SM3", app.getAppKey() + plainSecret + ts + nonce, null);

        InterfaceTestRequest signed = new InterfaceTestRequest();
        signed.setBody(req.getBody());
        signed.setContentType(req.getContentType());
        java.util.Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-App-Key", app.getAppKey());
        headers.put("X-Signature", signature);
        headers.put("X-Timestamp", String.valueOf(ts));
        headers.put("X-Nonce", nonce);
        headers.put("X-Env", env);
        if (req.getHeaders() != null) {
            headers.putAll(req.getHeaders());
        }
        signed.setHeaders(headers);

        int connect = pickTimeout(effective.getConnectTimeout(), api.getTimeoutMs(), FALLBACK_CONNECT_TIMEOUT_MS);
        int read = pickTimeout(effective.getReadTimeout(), api.getTimeoutMs(), FALLBACK_READ_TIMEOUT_MS);
        // 网关要多跑鉴权/限流/权限/日志，读超时给足，否则"网关慢"会被误判成"后端不通"
        read = Math.max(read, GATEWAY_MIN_READ_TIMEOUT_MS);
        result.setConnectTimeout(connect);
        result.setReadTimeout(read);
        result.setRetryCount(0);
        result.note("网关客户端超时：连接 " + connect + "ms / 读取 " + read + "ms（读取下限 "
                + GATEWAY_MIN_READ_TIMEOUT_MS + "ms，避免把网关链路耗时误判为后端不通）。");

        // ④ 发请求；签名只在页面上露出前 8 位（可用签名不应长期留在界面上）
        String masked = signature.length() > 8 ? signature.substring(0, 8) + "***" : "***";
        result.getRequestHeaders().put("X-App-Key", app.getAppKey());
        result.getRequestHeaders().put("X-Signature", masked);
        result.getRequestHeaders().put("X-Timestamp", String.valueOf(ts));
        result.getRequestHeaders().put("X-Nonce", nonce);
        result.getRequestHeaders().put("X-Env", env);

        send(api.getRequestMethod(), target, signed, result, connect, read);
    }

    /**
     * 挑选试调用的应用。
     *
     * <p>选择顺序（每一档都会往 {@code notes} 里写清楚，页面直接可见"为什么用了这个应用"）：
     * <ol>
     *   <li>请求显式指定 appId ⇒ 直接用（不存在则报错）；</li>
     *   <li>否则找「对该接口在该环境下有<b>有效授权</b>」的应用（{@code app_api_grant} status=1 且有效期覆盖今天）；</li>
     *   <li>再退回存量快照 {@code app_api_permission} status=1；</li>
     *   <li>最后退回任意启用中的应用，并明确提示"网关大概率返回 403 无权调用" ——
     *       这本身就是一次有价值的验证（证明权限拦截生效），所以不直接报错。</li>
     * </ol></p>
     */
    private App pickApp(Long apiId, String env, Long requestedAppId, InterfaceTestResult result) {
        if (requestedAppId != null) {
            App app = appMapper.selectById(requestedAppId);
            if (app == null) {
                throw GatewayException.badRequest("指定的应用不存在: id=" + requestedAppId);
            }
            result.note("按指定应用试调：appId=" + requestedAppId + "（" + app.getAppName() + "）");
            return app;
        }

        // ② 有有效授权的应用（保序去重）
        Set<Long> candidates = new LinkedHashSet<>();
        List<AppApiGrant> grants = appApiGrantMapper.selectList(new QueryWrapper<AppApiGrant>()
                .eq("api_id", apiId)
                .eq("env_code", env)
                .eq("status", 1)
                .orderByAsc("id"));
        LocalDate today = LocalDate.now();
        if (grants != null) {
            for (AppApiGrant g : grants) {
                if (g.getAppId() == null) {
                    continue;
                }
                if (g.getValidFrom() != null && g.getValidFrom().isAfter(today)) {
                    continue;
                }
                if (g.getValidTo() != null && g.getValidTo().isBefore(today)) {
                    continue;
                }
                candidates.add(g.getAppId());
            }
        }
        App picked = firstEnabled(candidates);
        if (picked != null) {
            result.note("自动选应用：该接口在 " + env + " 环境下有 " + candidates.size()
                    + " 个有效授权应用，取首个启用的「" + picked.getAppName() + "」。");
            return picked;
        }

        // ③ 存量快照
        List<AppApiPermission> perms = appApiPermissionMapper.selectList(new QueryWrapper<AppApiPermission>()
                .eq("interface_id", apiId)
                .eq("status", 1));
        Set<Long> snapshotIds = new LinkedHashSet<>();
        if (perms != null) {
            for (AppApiPermission p : perms) {
                if (p.getAppId() != null) {
                    snapshotIds.add(p.getAppId());
                }
            }
        }
        picked = firstEnabled(snapshotIds);
        if (picked != null) {
            result.note("自动选应用：未找到 " + env + " 环境下的有效授权，回退到存量授权快照（app_api_permission）"
                    + "，取「" + picked.getAppName() + "」。");
            return picked;
        }

        // ④ 兜底：任意启用中的应用，明确提示预期 403
        List<App> enabled = appMapper.selectList(new QueryWrapper<App>()
                .eq("status", 1)
                .orderByAsc("id"));
        if (enabled != null && !enabled.isEmpty()) {
            App any = enabled.get(0);
            result.note("未找到任何针对该接口的授权（" + env + " 环境）。已退用启用中的应用「" + any.getAppName()
                    + "」发起试调：网关**预期返回 403「无权调用此接口」**，这正好验证权限拦截是否生效。");
            return any;
        }
        return null;
    }

    /** 从候选 appId 里取第一个"存在且启用(status=1)"的应用 */
    private App firstEnabled(Set<Long> appIds) {
        for (Long id : appIds) {
            App a = appMapper.selectById(id);
            if (a != null && a.getStatus() != null && a.getStatus() == 1) {
                return a;
            }
        }
        return null;
    }

    // =====================================================================
    // HTTP 发送
    // =====================================================================

    /** 发送请求并把结果（状态码/耗时/报文/异常）写进 result */
    private void send(String method, String url, InterfaceTestRequest req,
                      InterfaceTestResult result, int connectTimeout, int readTimeout) {
        HttpRequestBase httpReq = buildRequest(method, url);
        if (httpReq == null) {
            result.setError("不支持的请求方法: " + method);
            return;
        }
        // 超时：连接 + 读取 + 连接池获取，三者一致
        httpReq.setConfig(RequestConfig.custom()
                .setConnectTimeout(connectTimeout)
                .setSocketTimeout(readTimeout)
                .setConnectionRequestTimeout(connectTimeout)
                .build());

        String contentType = StringUtils.hasText(req.getContentType())
                ? req.getContentType() : "application/json";
        if (req.getHeaders() != null) {
            for (Map.Entry<String, String> e : req.getHeaders().entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                // 签名头不进 requestHeaders（页面展示的是掩码版），但必须真实送出
                httpReq.setHeader(e.getKey(), e.getValue());
                result.getRequestHeaders().putIfAbsent(e.getKey(), e.getValue());
            }
        }

        // 请求体：GET/DELETE 不带实体
        if (req.getBody() != null && !req.getBody().isEmpty()
                && !"GET".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            StringEntity entity = new StringEntity(req.getBody(), StandardCharsets.UTF_8);
            entity.setContentType(contentType);
            if (httpReq instanceof HttpPost) {
                ((HttpPost) httpReq).setEntity(entity);
            } else if (httpReq instanceof HttpPut) {
                ((HttpPut) httpReq).setEntity(entity);
            }
        }
        if (!httpReq.containsHeader("Content-Type")
                && !"GET".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            httpReq.setHeader("Content-Type", contentType);
        }

        long start = System.currentTimeMillis();
        try (CloseableHttpResponse response = httpClient.execute(httpReq)) {
            int code = response.getStatusLine().getStatusCode();
            String text = response.getEntity() == null
                    ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            result.setCostMs(System.currentTimeMillis() - start);
            result.setStatusCode(code);
            result.setSuccess(code >= 200 && code < 400);
            if (text != null && text.length() > MAX_BODY_CHARS) {
                result.setResponseBody(text.substring(0, MAX_BODY_CHARS));
                result.setTruncated(true);
                result.note("响应报文超过 " + MAX_BODY_CHARS + " 字符，已截断展示。");
            } else {
                result.setResponseBody(text);
            }
        } catch (Exception e) {
            result.setCostMs(System.currentTimeMillis() - start);
            result.setSuccess(false);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result.setError("请求失败: " + msg);
            log.warn("Interface test failed: url={}, cause={}", url, msg);
        }
    }

    /** 按方法名构造请求对象（不支持的方法返回 null） */
    private HttpRequestBase buildRequest(String method, String url) {
        String m = method == null ? "GET" : method.trim().toUpperCase();
        switch (m) {
            case "GET":
                return new HttpGet(url);
            case "POST":
                return new HttpPost(url);
            case "PUT":
                return new HttpPut(url);
            case "DELETE":
                return new HttpDelete(url);
            default:
                return null;
        }
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /** 超时取值：生效配置 → 接口自身 timeoutMs → 兜底默认 */
    private int pickTimeout(Integer fromConfig, Integer fromInterface, int fallback) {
        if (fromConfig != null && fromConfig > 0) {
            return fromConfig;
        }
        if (fromInterface != null && fromInterface > 0) {
            return fromInterface;
        }
        return fallback > 0 ? fallback : DEFAULT_TIMEOUT_MS;
    }

    /** 生效配置来源的人话描述，便于在结果里直接展示 */
    private String describeSource(EffectiveEnvConfig e) {
        if (EffectiveEnvConfig.SOURCE_GROUP.equals(e.getSourceType())) {
            return "，上游地址来自分组配置「" + (e.getSourcePath() != null ? e.getSourcePath() : e.getSourceGroupName()) + "」";
        }
        if (EffectiveEnvConfig.SOURCE_INTERFACE.equals(e.getSourceType())) {
            return "，上游地址来自接口级环境配置";
        }
        return "，未命中任何环境配置";
    }

    /** 解密数据库里的 AppSecret；失败按明文处理（兼容历史未加密数据，与 AppAuthHandler 同口径） */
    private String decryptSecret(String stored) {
        if (!StringUtils.hasText(stored)) {
            return null;
        }
        try {
            return cryptoService.decrypt("AES", stored, CryptoKeyUtil.toBase64Key(aesDbKey),
                    null, "ECB", "PKCS5Padding");
        } catch (Exception e) {
            return stored;
        }
    }

    /** 推导自身基址：优先配置，其次从当前请求的 scheme://host:port 拼 */
    private String resolveSelfBase(HttpServletRequest request) {
        if (StringUtils.hasText(selfBaseUrl)) {
            return selfBaseUrl.trim().replaceAll("/+$", "");
        }
        if (request == null) {
            return null;
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    @PreDestroy
    public void destroy() {
        try {
            httpClient.close();
        } catch (Exception e) {
            log.warn("Close interface-test httpClient failed: {}", e.getMessage());
        }
    }
}
