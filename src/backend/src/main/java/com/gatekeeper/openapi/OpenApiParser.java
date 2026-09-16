package com.gatekeeper.openapi;

import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.exception.GatewayException;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI 3.x / Swagger 3.0 文档解析器（T18）。
 *
 * <p>把一份 spec 读成 {@link ParsedOperation} 列表，<b>只做解析与映射，不碰数据库</b>，
 * 因此可以脱离 Spring 容器单测（见 {@code OpenApiParserTest}）。</p>
 *
 * <h3>为什么用 swagger-core 而不是手写解析</h3>
 * <p>本仓已有 {@code knife4j-openapi3-spring-boot-starter}，其传递依赖里就带着
 * {@code io.swagger.core.v3:swagger-core / swagger-models}，所以
 * {@link Json}/{@link Yaml} 这两个官方 mapper 是<b>现成可用、零新增依赖</b>的。
 * 手写一个 YAML/JSON 遍历器不仅要多写几百行，还必然漏掉
 * {@code $ref} / {@code allOf} / {@code oneOf} 这些真实 spec 里高频出现的结构。</p>
 *
 * <h3>刻意的取舍（都写在对应方法的注释里）</h3>
 * <ol>
 *   <li><b>query / path 参数并入 Request 分区（paramType=3）</b>：
 *   控制台只有 Header/Request/Response/Error 四个分区（{@code utils/enum.js} 的
 *   {@code paramType} 只列了 1/3/4/5）。若按 DB 枚举写成 2(QUERY)，用户"导入了却看不到"。</li>
 *   <li><b>不改写 {@code backendUrl}</b>：OpenAPI 的 {@code servers} 是**对外基地址**，
 *   不一定是网关该转发的内部上游；猜错会直接把流量打到网关自己（回环）。
 *   故留空，由用户在接口详情里补，同时在结果里回显 {@code servers} 供参考。</li>
 *   <li><b>不创建 api_version</b>：导入只建立"接口资产 + 参数契约"，版本由人显式创建。</li>
 *   <li><b>长度按"加密后"算</b>：T17 会把 path / field_name / example / description
 *   加密落库，密文比明文长约 1.35~1.4 倍。所有截断上限都按密文能塞进列宽反推
 *   （详见各 {@code MAX_*} 常量注释），否则导入会撞 {@code Data too long}。</li>
 * </ol>
 *
 * @author GateKeeper
 * @since T18 (OpenAPI 导入)
 */
@Slf4j
@Component
public class OpenApiParser {

    /** HTTP 方法处理顺序（稳定输出，便于结果表可预期） */
    private static final String[] METHOD_ORDER =
            {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"};

    /** 单份文档最多导入的 operation 数（防超大 spec 拖垮单次请求） */
    private static final int MAX_OPERATIONS = 500;

    /** 单个分区（header/request/response/error）最多保留的参数条数 */
    private static final int MAX_PARAMS_PER_PARTITION = 200;

    /** nested object 展开的最大层级（超过则只记父字段本身） */
    private static final int MAX_DEPTH = 3;

    // ---- 长度上限：均按「T17 加密后的密文仍能落进列宽」反推 ----
    /** api_interface.interface_name varchar(128)，界面 maxlength=64 ⇒ 取 64 保证编辑往返不被截 */
    private static final int MAX_NAME = 64;
    /** api_interface.description varchar(512)，界面 maxlength=200 ⇒ 同上取 200 */
    private static final int MAX_DESC = 200;
    /**
     * api_interface.interface_path varchar(512)，且该列会被加密
     * （{@code enc:v1:} + 24 字符 IV + base64 密文）⇒ 明文超过约 344 字符必然溢出，取 300 留余量。
     */
    private static final int MAX_PATH = 300;
    /** api_param.field_name varchar(512) 且加密 ⇒ 字段名取 128（真实字段名远短于此） */
    private static final int MAX_FIELD_NAME = 128;
    /** api_param.example varchar(1024) 且加密 ⇒ 明文上限约 736，取 512 */
    private static final int MAX_EXAMPLE = 512;
    /** api_param.description varchar(1024) 且加密 ⇒ 同上取 512 */
    private static final int MAX_PARAM_DESC = 512;
    /** api_param.error_code varchar(64) */
    private static final int MAX_ERROR_CODE = 64;

    /** paramType：Header */
    private static final int PT_HEADER = 1;
    /** paramType：Request 入参（body + query + path 合并到此分区） */
    private static final int PT_REQUEST = 3;
    /** paramType：Response 出参 */
    private static final int PT_RESPONSE = 4;
    /** paramType：Error 错误码 */
    private static final int PT_ERROR = 5;

    /**
     * 解析一份 OpenAPI 文档。
     *
     * @param content  文件字节（UTF-8，容 BOM）
     * @param fileName 原始文件名（仅用于判断 JSON / YAML，可为 null）
     * @return 解析结果（文档元信息 + operation 列表）
     * @throws GatewayException 400 —— 内容为空 / 是 Swagger 2.0 / 不是合法 JSON/YAML / 缺少 openapi 版本字段
     */
    public Spec parse(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw GatewayException.badRequest("导入文件内容为空");
        }
        String text = stripBom(new String(content, StandardCharsets.UTF_8)).trim();
        if (!StringUtils.hasText(text)) {
            throw GatewayException.badRequest("导入文件内容为空");
        }
        if (text.length() > 8 * 1024 * 1024) {
            throw GatewayException.badRequest("导入文件过大（上限 8MB），请拆分后分批导入");
        }
        rejectNonOpenApi3(text);

        OpenAPI api = read(text, fileName);
        if (api == null) {
            throw GatewayException.badRequest("OpenAPI 文档解析失败：内容不是合法的 JSON / YAML");
        }

        Spec spec = new Spec();
        spec.setOpenapiVersion(api.getOpenapi());
        if (api.getInfo() != null) {
            spec.setTitle(api.getInfo().getTitle());
            spec.setVersion(api.getInfo().getVersion());
        }
        spec.setServerUrl(firstServerUrl(api));

        List<ParsedOperation> ops = new ArrayList<>();
        Paths paths = api.getPaths();
        if (paths != null) {
            for (Map.Entry<String, PathItem> e : paths.entrySet()) {
                collect(api, e.getKey(), e.getValue(), ops);
            }
        }
        spec.setOperations(ops);
        if (ops.isEmpty()) {
            throw GatewayException.badRequest("文档里没有可导入的接口（paths 为空，或每个 path 下都没有操作）");
        }
        if (ops.size() >= MAX_OPERATIONS) {
            log.warn("OpenAPI parse hit MAX_OPERATIONS={}, 后续操作被忽略", MAX_OPERATIONS);
        }
        log.info("OpenAPI parsed: openapi={}, title={}, operations={}",
                spec.getOpenapiVersion(), spec.getTitle(), ops.size());
        return spec;
    }

    // =====================================================================
    // 文档读取
    // =====================================================================

    /**
     * 按「文件名提示 + 内容首字符」选择 mapper，失败后自动改用另一种再试一次。
     *
     * <p>JSON 是 YAML 的子集，所以两种 mapper 有一定交叠；双试可以容忍
     * 「把 JSON 存成 .yaml」这类常见的用户失误，而不是直接报"解析失败"。</p>
     */
    private OpenAPI read(String text, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        boolean byNameYaml = lower.endsWith(".yaml") || lower.endsWith(".yml");
        boolean byNameJson = lower.endsWith(".json");
        boolean byContentJson = text.charAt(0) == '{' || text.charAt(0) == '[';

        boolean jsonFirst = byNameJson || (!byNameYaml && byContentJson);
        OpenAPI first = tryRead(text, jsonFirst);
        if (first != null) {
            return first;
        }
        return tryRead(text, !jsonFirst);
    }

    private OpenAPI tryRead(String text, boolean asJson) {
        try {
            return asJson
                    ? Json.mapper().readValue(text, OpenAPI.class)
                    : Yaml.mapper().readValue(text, OpenAPI.class);
        } catch (Exception e) {
            log.debug("OpenAPI read as {} failed: {}", asJson ? "JSON" : "YAML", e.getMessage());
            return null;
        }
    }

    /**
     * 在解析前先做一次「这是不是 OpenAPI 3.x」的文本级判定。
     *
     * <p>🔴 <b>为什么必须靠原文而不是靠解析后的对象</b>：
     * swagger-models 的 {@code OpenAPI} 把 {@code openapi} 字段
     * <b>默认初始化成了 {@code "3.0.1"}</b>。因此一份"压根没有版本字段"的文档
     * 反序列化后 {@code getOpenapi()} 依然非空 —— 把「缺版本字段」的判断写在对象上
     * 是<b>永远不会触发的死代码</b>（实测：这样的文档会被静默当成 3.0.1 收下）。
     * 只能回到原文里看这个键到底有没有出现。</p>
     *
     * <p><b>为什么先判 Swagger 2.0</b>：{@code swagger} 是 v2 独有的键。
     * 若反过来先判「有没有 openapi」，一份 2.0 文档只要描述里出现过 "openapi" 字样
     * 就能溜过第一关，再被对象层的默认值兜成"合法 3.0.1"，最终导入一堆空壳接口。
     * 判定顺序本身就是防线。</p>
     *
     * <p>键的识别用「{@code "openapi"}（JSON）或 {@code openapi:}（YAML）」而非裸词：
     * 后者会把描述文本里提到的 openapi 也算成"有版本字段"。</p>
     */
    private void rejectNonOpenApi3(String text) {
        if (text.contains("\"swagger\"") || text.contains("swagger:")) {
            throw GatewayException.badRequest(
                    "检测到 Swagger 2.0 文档（swagger: \"2.0\"）：当前仅支持 OpenAPI 3.x / Swagger 3.0，"
                            + "请先用 swagger2openapi 等工具转换为 3.0 后再导入");
        }
        if (!text.contains("\"openapi\"") && !text.contains("openapi:")) {
            throw GatewayException.badRequest(
                    "不是 OpenAPI 3.x 文档：顶层缺少 openapi 版本字段（如 openapi: 3.0.1）。"
                            + "当前仅支持 OpenAPI 3.x / Swagger 3.0");
        }
    }

    private String stripBom(String s) {
        return s != null && s.length() > 0 && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    private String firstServerUrl(OpenAPI api) {
        if (api.getServers() == null) {
            return null;
        }
        for (Server s : api.getServers()) {
            if (s != null && StringUtils.hasText(s.getUrl())) {
                return s.getUrl();
            }
        }
        return null;
    }

    // =====================================================================
    // path / operation 遍历
    // =====================================================================

    private void collect(OpenAPI api, String path, PathItem item, List<ParsedOperation> ops) {
        if (item == null || !StringUtils.hasText(path)) {
            return;
        }
        addOp(api, ops, path, "GET", item.getGet(), item);
        addOp(api, ops, path, "POST", item.getPost(), item);
        addOp(api, ops, path, "PUT", item.getPut(), item);
        addOp(api, ops, path, "PATCH", item.getPatch(), item);
        addOp(api, ops, path, "DELETE", item.getDelete(), item);
        addOp(api, ops, path, "HEAD", item.getHead(), item);
        addOp(api, ops, path, "OPTIONS", item.getOptions(), item);
        addOp(api, ops, path, "TRACE", item.getTrace(), item);
    }

    private void addOp(OpenAPI api, List<ParsedOperation> ops, String path, String method,
                       Operation op, PathItem item) {
        if (op == null) {
            return;
        }
        if (ops.size() >= MAX_OPERATIONS) {
            return;
        }
        ops.add(toOperation(api, path, method, op, item));
    }

    private ParsedOperation toOperation(OpenAPI api, String path, String method,
                                        Operation op, PathItem item) {
        ParsedOperation po = new ParsedOperation();
        po.setMethod(method);
        po.setPath(truncate(path, MAX_PATH));
        po.setName(truncate(resolveName(path, method, op), MAX_NAME));
        po.setDescription(truncate(pick(op.getDescription(), op.getSummary()), MAX_DESC));
        po.setSourceLocation("paths." + path + "." + method.toLowerCase());

        // 参数：path 级 parameters 与 operation 级 parameters 都要收（后者覆盖前者）
        List<Parameter> params = new ArrayList<>();
        if (item.getParameters() != null) {
            params.addAll(item.getParameters());
        }
        if (op.getParameters() != null) {
            params.addAll(op.getParameters());
        }

        // 顺序很关键：先 body（主契约）、再 query、再 path —— 同名时 body 胜出（见 §去重）
        RequestBody body = resolveRequestBody(api, op.getRequestBody());
        po.setRequestParamType(resolveRequestParamType(body, params));
        if (body != null) {
            mapRequestBody(api, body, po);
        }
        mapParameters(api, params, po);
        mapResponses(api, op.getResponses(), po);
        return po;
    }

    /**
     * 接口名称三级回退：{@code summary} → {@code operationId} → {@code "METHOD path"}。
     *
     * <p>很多代码生成的 spec 三者都没有，此时用「方法 + 路径」兜底，
     * 保证列表里不会出现空名称行（{@code interface_name} 是 NOT NULL）。</p>
     */
    private String resolveName(String path, String method, Operation op) {
        String s = pick(op.getSummary(), op.getOperationId());
        return StringUtils.hasText(s) ? s.trim() : (method + " " + path);
    }

    /**
     * 推导 {@code api_interface.request_param_type}（合法值 JSON / FORM / QUERY）。
     */
    private String resolveRequestParamType(RequestBody body, List<Parameter> params) {
        if (body != null && body.getContent() != null && !body.getContent().isEmpty()) {
            for (String media : body.getContent().keySet()) {
                if (media == null) {
                    continue;
                }
                String m = media.toLowerCase();
                if (m.contains("form-urlencoded") || m.contains("multipart")) {
                    return "FORM";
                }
            }
            return "JSON";
        }
        if (params != null) {
            for (Parameter p : params) {
                if (p != null && "query".equalsIgnoreCase(p.getIn())) {
                    return "QUERY";
                }
            }
        }
        // 与新建接口表单的默认口径保持一致（该列 DB 默认值即 JSON）
        return "JSON";
    }

    // =====================================================================
    // requestBody / parameters / responses 映射
    // =====================================================================

    private void mapRequestBody(OpenAPI api, RequestBody body, ParsedOperation po) {
        if (body.getContent() == null || body.getContent().isEmpty()) {
            return;
        }
        MediaType mt = preferredMediaType(body.getContent());
        if (mt == null || mt.getSchema() == null) {
            return;
        }
        boolean required = Boolean.TRUE.equals(body.getRequired());
        String desc = body.getDescription();
        expandSchema(api, mt.getSchema(), PT_REQUEST, required, desc, null, po);
    }

    /**
     * 参数映射：header/cookie → Header 分区；query/path → Request 分区。
     *
     * <p>query 与 path 用 {@code [query] } / {@code [path] } 前缀写进<b>描述</b>
     * 而不是另开分区 —— 控制台只渲染 4 个分区，另开分区等于把参数藏起来；
     * 但两者语义确实不同，用描述前缀保留这个信息，且不污染 {@code field_name} 本身。</p>
     */
    private void mapParameters(OpenAPI api, List<Parameter> params, ParsedOperation po) {
        if (params == null) {
            return;
        }
        for (Parameter raw : params) {
            Parameter p = resolveParameter(api, raw);
            if (p == null || !StringUtils.hasText(p.getName())) {
                continue;
            }
            String in = p.getIn() == null ? "query" : p.getIn().toLowerCase();
            int paramType;
            String loc;
            switch (in) {
                case "header":
                    paramType = PT_HEADER;
                    loc = null;
                    break;
                case "cookie":
                    paramType = PT_HEADER;
                    loc = "cookie";
                    break;
                case "path":
                    paramType = PT_REQUEST;
                    loc = "path";
                    break;
                case "query":
                default:
                    paramType = PT_REQUEST;
                    loc = "query";
                    break;
            }
            Schema<?> schema = resolveSchema(api, p.getSchema(), new HashSet<String>());
            ApiParamDto dto = baseParam(p.getName(), schema,
                    Boolean.TRUE.equals(p.getRequired()),
                    pick(p.getDescription(), schema == null ? null : schema.getDescription()),
                    p.getExample());
            if (loc != null) {
                dto.setDescription(locPrefix(loc, dto.getDescription()));
            }
            add(po, paramType, dto);
        }
    }

    /**
     * 响应映射：2xx → Response 分区；其余（4xx/5xx/default）→ Error 分区。
     *
     * <p>Error 分区按 {@code error_code} 的语义组织：{@code fieldName} 与
     * {@code errorCode} 都写响应码（该列 NOT NULL 且界面按它展示），
     * {@code httpStatus} 写数字形态，描述写 spec 里的 response description。</p>
     */
    private void mapResponses(OpenAPI api, ApiResponses responses, ParsedOperation po) {
        if (responses == null || responses.isEmpty()) {
            return;
        }
        for (Map.Entry<String, ApiResponse> e : responses.entrySet()) {
            String code = e.getKey();
            ApiResponse raw = e.getValue();
            if (!StringUtils.hasText(code) || raw == null) {
                continue;
            }
            ApiResponse r = resolveResponse(api, raw, code);
            Integer httpStatus = toHttpStatus(code);
            boolean success = code.startsWith("2");

            if (success) {
                if (r == null || r.getContent() == null || r.getContent().isEmpty()) {
                    continue; // 只有描述没有结构的 2xx（如 204）不产生出参行
                }
                MediaType mt = preferredMediaType(r.getContent());
                if (mt == null || mt.getSchema() == null) {
                    continue;
                }
                expandSchema(api, mt.getSchema(), PT_RESPONSE, false,
                        r.getDescription(), httpStatus, po);
            } else {
                ApiParamDto dto = new ApiParamDto();
                dto.setFieldName(truncate(code, MAX_ERROR_CODE));
                dto.setErrorCode(truncate(code, MAX_ERROR_CODE));
                dto.setHttpStatus(httpStatus);
                dto.setFieldType("string");
                dto.setRequired(0);
                dto.setSensitive(0);
                dto.setDescription(truncate(r == null ? null : r.getDescription(), MAX_PARAM_DESC));
                add(po, PT_ERROR, dto);
            }
        }
    }

    // =====================================================================
    // schema 展开
    // =====================================================================

    /**
     * 把一个 schema 展开成扁平行（nested object 用 {@code parent.child} 点号命名）。
     *
     * @param api        文档（用于解析 {@code $ref}）
     * @param schema     待展开的 schema（可能是 {@code $ref} 或 {@code allOf}）
     * @param paramType  目标分区
     * @param required   该 schema 整体是否必填（body 的 required）
     * @param desc       schema 级描述（作为子字段描述的兜底）
     * @param httpStatus 出参的 HTTP 状态码（入参传 null）
     * @param po         输出
     */
    private void expandSchema(OpenAPI api, Schema<?> schema, int paramType, boolean required,
                              String desc, Integer httpStatus, ParsedOperation po) {
        Schema<?> flat = flatten(api, schema, new HashSet<String>());
        if (flat == null) {
            return;
        }
        Map<String, Schema> props = flat.getProperties();
        if (props == null || props.isEmpty()) {
            // 顶层直接是标量 / 数组：记一条以 schema 描述为名的行是不合适的（无字段名），
            // 故只在有 properties 时展开；标量 body 交给 requestParamType 表达即可。
            if (StringUtils.hasText(desc)) {
                po.getWarnings().add("requestBody/响应体没有可展开的属性（仅描述：" + truncate(desc, 40) + "）");
            }
            return;
        }
        walk(api, flat, "", 0, paramType, required, httpStatus, po);
    }

    private void walk(OpenAPI api, Schema<?> schema, String prefix, int depth, int paramType,
                      boolean required, Integer httpStatus, ParsedOperation po) {
        if (schema == null || depth > MAX_DEPTH) {
            return;
        }
        Set<String> requiredNames = new LinkedHashSet<>();
        if (schema.getRequired() != null) {
            requiredNames.addAll(schema.getRequired());
        }
        Map<String, Schema> props = schema.getProperties();
        if (props == null || props.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Schema> e : props.entrySet()) {
            String name = e.getKey();
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Schema<?> child = e.getValue();
            String full = StringUtils.hasText(prefix) ? prefix + "." + name : name;
            Schema<?> cflat = flatten(api, child, new HashSet<String>());
            boolean childRequired = required && requiredNames.contains(name);
            String cdesc = cflat == null ? null : cflat.getDescription();

            boolean nested = cflat != null && cflat.getProperties() != null && !cflat.getProperties().isEmpty();
            if (nested && depth + 1 <= MAX_DEPTH) {
                walk(api, cflat, full, depth + 1, paramType, childRequired, httpStatus, po);
            } else {
                ApiParamDto dto = baseParam(full, cflat == null ? child : cflat,
                        childRequired, cdesc, null);
                if (httpStatus != null) {
                    dto.setHttpStatus(httpStatus);
                }
                add(po, paramType, dto);
            }
        }
    }

    /**
     * 解析 {@code $ref} 并合并 {@code allOf} 分支。
     *
     * <p>本仓没有 {@code swagger-parser}（它是独立 artifact），所以这里手工处理
     * 最常见的两类结构：{@code $ref → components.schemas} 与 {@code allOf} 继承。
     * {@code oneOf}/{@code anyOf} 取第一个分支 —— 导入的是"契约骨架"，
     * 多态结构在控制台里也无法表达，取一支比整体丢弃更有用。</p>
     *
     * @param seen 已展开的 ref 名（防自引用死循环）
     */
    private Schema<?> flatten(OpenAPI api, Schema<?> schema, Set<String> seen) {
        if (schema == null) {
            return null;
        }
        String ref = schema.get$ref();
        if (StringUtils.hasText(ref)) {
            String key = lastSegment(ref);
            if (!StringUtils.hasText(key) || !seen.add(key)) {
                return null; // 自引用/循环引用：停止展开
            }
            Schema<?> target = componentSchema(api, key);
            return target == null ? null : flatten(api, target, seen);
        }

        List<Schema> branches = null;
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            branches = schema.getAllOf();
        } else if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            branches = schema.getOneOf();
        } else if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            branches = schema.getAnyOf();
        }
        if (branches == null) {
            return schema;
        }

        // 🔴 两个由 swagger-models 实测出来的坑，决定了这里必须用「局部累积 + 末尾一次性 set」：
        //    ① setRequired(空列表) 之后 getRequired() 返回 null（空集合被归一成 null）⇒
        //       先 set 空 list 再 getRequired().addAll(...) 必 NPE；
        //    ② getRequired() 返回的是**拷贝**而非同一实例（实测 sameInstance=false）⇒
        //       往 getRequired() 的返回值里 addAll 是静默无效的，字段永远不会被改动。
        //    两条加起来说明：「读回自己刚 set 的集合」在这套模型里根本不可用。
        Map<String, Schema> mergedProps = new LinkedHashMap<>();
        List<String> mergedRequired = new ArrayList<>();
        boolean any = false;
        for (Schema<?> b : branches) {
            Schema<?> part = flatten(api, b, new HashSet<String>(seen));
            if (part == null || part.getProperties() == null) {
                continue;
            }
            any = true;
            mergedProps.putAll(part.getProperties());
            if (part.getRequired() != null) {
                mergedRequired.addAll(part.getRequired());
            }
        }
        if (!any) {
            return null;
        }
        ObjectSchema merged = new ObjectSchema();
        merged.setProperties(mergedProps);
        if (!mergedRequired.isEmpty()) {
            // 空集合不 set：设了也会被归一成 null，后续读 required 的分支反而更容易踩坑
            merged.setRequired(mergedRequired);
        }
        if (StringUtils.hasText(schema.getDescription())) {
            merged.setDescription(schema.getDescription());
        }
        return merged;
    }

    private Schema<?> componentSchema(OpenAPI api, String key) {
        Components c = api.getComponents();
        if (c == null || c.getSchemas() == null) {
            return null;
        }
        return c.getSchemas().get(key);
    }

    // =====================================================================
    // 单个参数构造
    // =====================================================================

    /**
     * 构造一行参数（三列内容 + 结构列）。
     *
     * @param name     字段名
     * @param schema   字段 schema（可为 null）
     * @param required 是否必填
     * @param desc     描述
     * @param explicit 显式示例值（parameter.example 优先级最高，可为 null）
     */
    private ApiParamDto baseParam(String name, Schema<?> schema, boolean required,
                                  String desc, Object explicit) {
        ApiParamDto dto = new ApiParamDto();
        dto.setFieldName(truncate(name, MAX_FIELD_NAME));
        dto.setFieldType(mapFieldType(schema));
        dto.setRequired(required ? 1 : 0);
        Object example = explicit != null ? explicit : schemaExample(schema);
        dto.setExample(truncate(exampleText(example), MAX_EXAMPLE));
        dto.setSensitive(0);
        dto.setDescription(truncate(appendTypeHint(desc, schema), MAX_PARAM_DESC));
        return dto;
    }

    /**
     * OpenAPI 类型 → 控制台 fieldType 口径（string/int/number/array/object/bool）。
     *
     * <p>{@code integer} 映射为 {@code int}（不是 integer）：控制台枚举里是 {@code int}，
     * 写成 integer 会让参数页的类型列显示空白。</p>
     */
    private String mapFieldType(Schema<?> s) {
        if (s == null) {
            return "string";
        }
        String t = s.getType();
        if (!StringUtils.hasText(t)) {
            if (s.getProperties() != null && !s.getProperties().isEmpty()) {
                return "object";
            }
            if (s.getItems() != null) {
                return "array";
            }
            return "string";
        }
        switch (t) {
            case "integer":
                return "int";
            case "number":
                return "number";
            case "boolean":
                return "bool";
            case "array":
                return "array";
            case "object":
                return "object";
            default:
                return "string";
        }
    }

    /**
     * 描述尾部补一句结构提示（数组元素类型 / 枚举取值范围）。
     *
     * <p>这些信息是真实契约的一部分，但 {@code api_param} 没有对应列，
     * 丢掉了很可惜、写成额外字段又没地方放 ⇒ 追加到描述里。</p>
     */
    private String appendTypeHint(String desc, Schema<?> s) {
        if (s == null) {
            return desc;
        }
        StringBuilder hint = new StringBuilder();
        if (s.getItems() != null) {
            hint.append("数组元素: ").append(mapFieldType(s.getItems()));
            if (StringUtils.hasText(s.getItems().getFormat())) {
                hint.append('(').append(s.getItems().getFormat()).append(')');
            }
        }
        if (s.getEnum() != null && !s.getEnum().isEmpty()) {
            if (hint.length() > 0) {
                hint.append("；");
            }
            hint.append("枚举: ").append(joinEnum(s.getEnum()));
        }
        if (hint.length() == 0) {
            return desc;
        }
        return StringUtils.hasText(desc) ? desc + "（" + hint + "）" : hint.toString();
    }

    private String joinEnum(List<?> values) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Object v : values) {
            if (n >= 20) {
                sb.append("…");
                break;
            }
            if (n > 0) {
                sb.append('/');
            }
            sb.append(String.valueOf(v));
            n++;
        }
        return sb.toString();
    }

    /** 示例值优先级：{@code example} → {@code default} → 枚举首个 */
    private Object schemaExample(Schema<?> s) {
        if (s == null) {
            return null;
        }
        if (s.getExample() != null) {
            return s.getExample();
        }
        if (s.getDefault() != null) {
            return s.getDefault();
        }
        if (s.getEnum() != null && !s.getEnum().isEmpty()) {
            return s.getEnum().get(0);
        }
        return null;
    }

    private String exampleText(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Map || o instanceof List) {
            try {
                return Json.mapper().writeValueAsString(o);
            } catch (Exception ignore) {
                return String.valueOf(o);
            }
        }
        return String.valueOf(o);
    }

    // =====================================================================
    // ref 解析
    // =====================================================================

    private Parameter resolveParameter(OpenAPI api, Parameter p) {
        if (p == null) {
            return null;
        }
        String ref = p.get$ref();
        if (StringUtils.hasText(ref)) {
            Components c = api.getComponents();
            if (c != null && c.getParameters() != null) {
                Object t = c.getParameters().get(lastSegment(ref));
                if (t instanceof Parameter) {
                    return (Parameter) t;
                }
            }
        }
        return p;
    }

    private RequestBody resolveRequestBody(OpenAPI api, RequestBody body) {
        if (body == null) {
            return null;
        }
        String ref = body.get$ref();
        if (StringUtils.hasText(ref)) {
            Components c = api.getComponents();
            if (c != null && c.getRequestBodies() != null) {
                Object t = c.getRequestBodies().get(lastSegment(ref));
                if (t instanceof RequestBody) {
                    return (RequestBody) t;
                }
            }
        }
        return body;
    }

    private ApiResponse resolveResponse(OpenAPI api, ApiResponse r, String code) {
        String ref = r.get$ref();
        if (StringUtils.hasText(ref)) {
            Components c = api.getComponents();
            if (c != null && c.getResponses() != null) {
                Object t = c.getResponses().get(lastSegment(ref));
                if (t instanceof ApiResponse) {
                    return (ApiResponse) t;
                }
            }
            return null;
        }
        return r;
    }

    private Schema<?> resolveSchema(OpenAPI api, Schema<?> s, Set<String> seen) {
        if (s == null) {
            return null;
        }
        String ref = s.get$ref();
        if (!StringUtils.hasText(ref)) {
            return s;
        }
        String key = lastSegment(ref);
        if (!StringUtils.hasText(key) || !seen.add(key)) {
            return null;
        }
        return componentSchema(api, key);
    }

    private String lastSegment(String ref) {
        if (ref == null) {
            return null;
        }
        int i = ref.lastIndexOf('/');
        return i < 0 ? ref : ref.substring(i + 1);
    }

    // =====================================================================
    // 工具
    // =====================================================================

    /**
     * 优先挑选结构化媒体类型：{@code application/json} > {@code *+json} > 其它第一个。
     */
    private MediaType preferredMediaType(Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        MediaType exact = content.get("application/json");
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, MediaType> e : content.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().contains("json")) {
                return e.getValue();
            }
        }
        for (MediaType mt : content.values()) {
            if (mt != null) {
                return mt;
            }
        }
        return null;
    }

    /**
     * 把参数放进对应分区；同名（同分区内）只保留第一条。
     *
     * <p>去重是必需的：把 query / path 并入 Request 分区后，{@code POST /users/{id}}
     * 这种"路径里有 id、body 里也有 id"的写法会出现同名行。
     * 调用顺序保证 body 先入 ⇒ body 字段胜出（它承载的契约信息更多）。</p>
     *
     * @return true=已写入；false=重复或超出分区上限（调用方无需区分）
     */
    private boolean add(ParsedOperation po, int paramType, ApiParamDto dto) {
        if (dto == null || !StringUtils.hasText(dto.getFieldName())) {
            return false;
        }
        List<ApiParamDto> target = partition(po, paramType);
        if (target == null) {
            return false;
        }
        if (target.size() >= MAX_PARAMS_PER_PARTITION) {
            po.getWarnings().add("分区 " + paramType + " 参数超过 " + MAX_PARAMS_PER_PARTITION + " 条，已截断");
            return false;
        }
        for (ApiParamDto exists : target) {
            if (dto.getFieldName().equals(exists.getFieldName())) {
                return false;
            }
        }
        dto.setSortOrder(target.size());
        dto.setParentId(0L);
        target.add(dto);
        return true;
    }

    private List<ApiParamDto> partition(ParsedOperation po, int paramType) {
        switch (paramType) {
            case PT_HEADER:
                return po.getHeader();
            case PT_REQUEST:
                return po.getRequest();
            case PT_RESPONSE:
                return po.getResponse();
            case PT_ERROR:
                return po.getError();
            default:
                return null;
        }
    }

    private String locPrefix(String loc, String desc) {
        String prefix = "[" + loc + "] ";
        return StringUtils.hasText(desc) ? prefix + desc : prefix.trim();
    }

    private Integer toHttpStatus(String code) {
        try {
            return Integer.valueOf(code.trim());
        } catch (Exception e) {
            return null; // "default" / "2XX" 这类非数字码
        }
    }

    private String pick(String a, String b) {
        if (StringUtils.hasText(a)) {
            return a.trim();
        }
        return StringUtils.hasText(b) ? b.trim() : null;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 解析结果：文档元信息 + 全部 operation。
     */
    @Data
    public static class Spec {
        /** 文档声明的 openapi 版本（如 3.0.1） */
        private String openapiVersion;
        /** info.title */
        private String title;
        /** info.version */
        private String version;
        /**
         * servers[0].url —— <b>只用于提示，不写进 backend_url</b>。
         * OpenAPI 的 servers 是"对外基地址"，常是网关自己的域名；
         * 直接当转发目标会造成回环。
         */
        private String serverUrl;
        /** 解析出的 operation 列表 */
        private List<ParsedOperation> operations = new ArrayList<>();
    }
}
