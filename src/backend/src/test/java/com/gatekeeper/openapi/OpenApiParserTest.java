package com.gatekeeper.openapi;

import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.exception.GatewayException;
// Spec 是 OpenApiParser 的嵌套类，同包也必须显式导入（否则按顶层类名找不到符号）
import com.gatekeeper.openapi.OpenApiParser.Spec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAPI 3.x 解析器单测（T18）。
 *
 * <p>本类只测 {@link OpenApiParser}「文档 → 中间表示」的映射，<b>不碰数据库</b>，
 * 因此可以直接 new 出来跑。落库编排的正确性在
 * {@code InterfaceImportServiceImplTest} 与端到端探针里覆盖。</p>
 *
 * <p><b>为什么断言得这么细</b>：解析器的产物会直接变成 {@code api_interface} /
 * {@code api_param} 的行。一旦映射错了（比如把 query 参数丢进一个界面不存在的分区），
 * 表现是"导入成功但参数页看不到"，属于最难排查的一类缺陷 —— 只能靠断言把口径钉住：</p>
 * <ul>
 *   <li>paramType 只有四档 1/3/4/5，query 与 path <b>必须并入 3</b>；</li>
 *   <li>fieldType 是 {@code int} / {@code bool}，<b>不是</b> {@code integer} / {@code boolean}；</li>
 *   <li>{@code backend_url} 由调用方决定，解析器不产出它（故这里只验 servers 被回显）；</li>
 *   <li>所有长度上限都按「T17 加密后的密文仍塞得进列宽」反推，故要验真的截断了。</li>
 * </ul>
 */
@DisplayName("OpenAPI 3.x 解析器")
class OpenApiParserTest {

    private static final int PT_HEADER = 1;
    private static final int PT_REQUEST = 3;
    private static final int PT_RESPONSE = 4;
    private static final int PT_ERROR = 5;

    private final OpenApiParser parser = new OpenApiParser();

    // =====================================================================
    // 基础设施
    // =====================================================================

    private Spec parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8), "api.json");
    }

    private Spec parseYaml(String yaml) {
        return parser.parse(yaml.getBytes(StandardCharsets.UTF_8), "api.yaml");
    }

    /** 组装一份最小可用文档：固定 info/servers，paths 与 components 由用例给。 */
    private static String spec(String pathsJson, String componentsJson) {
        return "{"
                + "\"openapi\":\"3.0.1\","
                + "\"info\":{\"title\":\"订单服务\",\"version\":\"1.2.3\"},"
                + "\"servers\":[{\"url\":\"https://gw.example.com/api\"}],"
                + "\"paths\":{" + pathsJson + "}"
                + (componentsJson == null ? "" : ",\"components\":" + componentsJson)
                + "}";
    }

    private static ParsedOperation only(Spec spec) {
        assertEquals(1, spec.getOperations().size(), "本用例只应有 1 个 operation");
        return spec.getOperations().get(0);
    }

    private static ApiParamDto find(List<ApiParamDto> list, String fieldName) {
        for (ApiParamDto d : list) {
            if (fieldName.equals(d.getFieldName())) {
                return d;
            }
        }
        return null;
    }

    // =====================================================================
    // 文档级：元信息与整批拒绝
    // =====================================================================

    @Test
    @DisplayName("最小 3.0 文档：方法/路径/名称/描述/定位串/文档元信息全部就位")
    void minimalSpec_parsesOperationAndMeta() {
        Spec spec = parse(spec("\"/users\":{\"get\":{"
                + "\"summary\":\"查询用户\",\"description\":\"按 id 查\",\"operationId\":\"getUser\"}}", null));

        assertEquals("3.0.1", spec.getOpenapiVersion());
        assertEquals("订单服务", spec.getTitle());
        assertEquals("1.2.3", spec.getVersion());
        assertEquals("https://gw.example.com/api", spec.getServerUrl());
        assertEquals(1, spec.getOperations().size());

        ParsedOperation o = only(spec);
        assertEquals("GET", o.getMethod());
        assertEquals("/users", o.getPath());
        assertEquals("查询用户", o.getName());
        assertEquals("按 id 查", o.getDescription());
        assertEquals("JSON", o.getRequestParamType(), "无 body 无 query 时入参类型兜底 JSON（与 DB 默认值一致）");
        assertEquals("paths./users.get", o.getSourceLocation());
    }

    @Test
    @DisplayName("名称三级回退：summary → operationId → 「METHOD path」")
    void name_fallsBackInThreeLevels() {
        Spec spec = parse(spec("\"/a\":{\"get\":{\"operationId\":\"opA\"}},"
                + "\"/b\":{\"post\":{}}", null));

        assertEquals(2, spec.getOperations().size());
        assertEquals("opA", findOp(spec, "GET").getName(), "无 summary 时用 operationId");
        assertEquals("POST /b", findOp(spec, "POST").getName(), "两者都无时用「方法 + 路径」兜底");
    }

    private static ParsedOperation findOp(Spec spec, String method) {
        for (ParsedOperation o : spec.getOperations()) {
            if (method.equals(o.getMethod())) {
                return o;
            }
        }
        throw new AssertionError("未找到 " + method + " operation");
    }

    @Test
    @DisplayName("YAML 文档同样可解析（openapi: 3.0.1）")
    void yamlSpec_isSupported() {
        String yaml = "openapi: 3.0.1\n"
                + "info:\n  title: 订单服务\n  version: 1.0.0\n"
                + "paths:\n"
                + "  /orders:\n"
                + "    post:\n"
                + "      summary: 创建订单\n"
                + "      requestBody:\n"
                + "        required: true\n"
                + "        content:\n"
                + "          application/json:\n"
                + "            schema:\n"
                + "              type: object\n"
                + "              required: [sku]\n"
                + "              properties:\n"
                + "                sku:\n"
                + "                  type: string\n"
                + "                qty:\n"
                + "                  type: integer\n";

        Spec spec = parseYaml(yaml);
        ParsedOperation o = only(spec);
        assertEquals("POST", o.getMethod());
        assertEquals("创建订单", o.getName());
        assertEquals(2, o.getRequest().size());
        assertEquals("int", find(o.getRequest(), "qty").getFieldType(), "integer 必须映射成界面口径的 int");
        assertEquals(1, find(o.getRequest(), "sku").getRequired(), "required 列表内的字段标必填");
        assertEquals(0, find(o.getRequest(), "qty").getRequired(), "不在 required 列表内即非必填");
    }

    @Test
    @DisplayName("文件名后缀与内容不符也能解析（JSON 存成 .yaml 的双试兜底）")
    void mapperRetry_toleratesWrongExtension() {
        String json = spec("\"/users\":{\"get\":{\"summary\":\"u\"}}", null);
        Spec spec = parser.parse(json.getBytes(StandardCharsets.UTF_8), "api.yaml");
        assertEquals(1, spec.getOperations().size());
    }

    @Test
    @DisplayName("Swagger 2.0 直接 400，并给出转换建议")
    void swagger2_isRejectedWithHint() {
        String swagger2 = "{\"swagger\":\"2.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
                + "\"paths\":{\"/a\":{\"get\":{}}}}";
        GatewayException ex = assertThrows(GatewayException.class, () -> parse(swagger2));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("Swagger 2.0"), "应点名版本，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("转换"), "应给出可照做的转换建议");
    }

    @Test
    @DisplayName("Swagger 2.0 文档即使正文出现 openapi 字样，也仍判为 2.0（判定顺序即防线）")
    void swagger2WithOpenapiWordElsewhere_stillRejected() {
        // 若先判「有没有 openapi」再判 swagger，这份文档会溜过第一关，
        // 再被 swagger-models 的默认版本号兜成"合法 3.0.1"，最终导入一堆空壳接口。
        String swagger2 = "{\"swagger\":\"2.0\","
                + "\"info\":{\"title\":\"从 openapi 2.0 迁移过来的服务\",\"version\":\"1\"},"
                + "\"paths\":{\"/a\":{\"get\":{}}}}";

        GatewayException ex = assertThrows(GatewayException.class, () -> parse(swagger2));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("Swagger 2.0"), ex.getMessage());
    }

    @Test
    @DisplayName("回显的是文档声明的版本号，而不是模型默认值")
    void openapiVersion_isTakenFromDocument() {
        String doc = "{"
                + "\"openapi\":\"3.0.3\","
                + "\"info\":{\"title\":\"t\",\"version\":\"1\"},"
                + "\"paths\":{\"/a\":{\"get\":{\"summary\":\"a\"}}}"
                + "}";
        assertEquals("3.0.3", parse(doc).getOpenapiVersion());
    }

    @Test
    @DisplayName("缺少 openapi 版本字段 → 400")
    void missingOpenapiField_isRejected() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> parse("{\"info\":{\"title\":\"t\"},\"paths\":{\"/a\":{\"get\":{}}}}"));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("OpenAPI 3"), ex.getMessage());
    }

    @Test
    @DisplayName("paths 为空 → 400（导了 0 条还回成功会误导用户）")
    void emptyPaths_isRejected() {
        GatewayException ex = assertThrows(GatewayException.class, () -> parse(spec("", null)));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("没有可导入的接口"), ex.getMessage());
    }

    @Test
    @DisplayName("path 下没有任何操作（只有 description 之类）→ 400")
    void pathWithoutOperation_isRejected() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> parse(spec("\"/a\":{\"description\":\"空壳\"}", null)));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("空内容 / 非 JSON-YAML 内容 → 400")
    void emptyOrGarbage_isRejected() {
        assertEquals(400, assertThrows(GatewayException.class,
                () -> parser.parse(new byte[0], "a.json")).getCode());
        assertEquals(400, assertThrows(GatewayException.class,
                () -> parser.parse("    ".getBytes(StandardCharsets.UTF_8), "a.json")).getCode());
        assertEquals(400, assertThrows(GatewayException.class,
                () -> parse("这不是一份 spec")).getCode());
    }

    @Test
    @DisplayName("超过 8MB → 400（明确提示拆分，而不是让 Tomcat 先崩）")
    void oversizeDocument_isRejected() {
        StringBuilder sb = new StringBuilder(9 * 1024 * 1024);
        sb.append(spec("\"/a\":{\"get\":{}}", null));
        while (sb.length() < 9 * 1024 * 1024) {
            // 必须用非空白填充：解析入口会先 trim()，补空格会被裁掉导致体积检查根本不触发
            sb.append('x');
        }
        GatewayException ex = assertThrows(GatewayException.class, () -> parse(sb.toString()));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("过大"), ex.getMessage());
    }

    // =====================================================================
    // 参数分区：paramType 口径是本节的核心契约
    // =====================================================================

    @Test
    @DisplayName("query / path 并入 Request(3)，用描述前缀保留语义；header → Header(1)")
    void parameterLocation_mapsToConsolePartitions() {
        String params = "\"parameters\":["
                + "{\"name\":\"X-Token\",\"in\":\"header\",\"required\":true,\"schema\":{\"type\":\"string\"}},"
                + "{\"name\":\"page\",\"in\":\"query\",\"schema\":{\"type\":\"integer\"}},"
                + "{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"schema\":{\"type\":\"string\"}}"
                + "]";
        Spec spec = parse(spec("\"/users/{id}\":{\"get\":{" + params + "}}", null));
        ParsedOperation o = only(spec);

        assertEquals(1, o.getHeader().size(), "只有 header 进 Header 分区");
        assertEquals("X-Token", o.getHeader().get(0).getFieldName());

        // 🔴 关键：控制台没有 paramType=2(QUERY) 这一档，query/path 必须并入 3，
        //    否则"导入成功但参数页看不到"。
        assertEquals(2, o.getRequest().size());
        assertEquals("[query]", find(o.getRequest(), "page").getDescription(), "query 语义靠描述前缀保留");
        assertEquals("[path]", find(o.getRequest(), "id").getDescription(), "path 语义靠描述前缀保留");
        assertEquals(0, o.getResponse().size());
        assertEquals(0, o.getError().size());
    }

    @Test
    @DisplayName("path 级与 operation 级 parameters 都收，且同名时只保留第一条")
    void parameters_deduplicatedWithinPartition() {
        String spec = "{"
                + "\"openapi\":\"3.0.1\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
                + "\"paths\":{\"/u\":{"
                + "  \"parameters\":[{\"name\":\"tenant\",\"in\":\"header\",\"description\":\"path 级\","
                + "                   \"schema\":{\"type\":\"string\"}}],"
                + "  \"get\":{\"parameters\":["
                + "     {\"name\":\"tenant\",\"in\":\"header\",\"description\":\"op 级\",\"schema\":{\"type\":\"string\"}},"
                + "     {\"name\":\"kw\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}]}"
                + "}}"
                + "}";
        ParsedOperation o = only(parse(spec));

        assertEquals(1, o.getRequest().size(), "只有 query 的 kw 进 Request 分区");
        assertEquals(1, o.getHeader().size(), "同名 tenant（path 级 / op 级）只保留一条");
    }

    @Test
    @DisplayName("同名字段：body 先入 ⇒ body 胜出（它承载的契约信息更多）")
    void bodyWinsOverQueryOnNameConflict() {
        String spec = "{"
                + "\"openapi\":\"3.0.1\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
                + "\"paths\":{\"/u\":{\"post\":{"
                + "  \"parameters\":[{\"name\":\"id\",\"in\":\"query\",\"description\":\"来自 query\","
                + "                   \"schema\":{\"type\":\"string\"}}],"
                + "  \"requestBody\":{\"content\":{\"application/json\":{\"schema\":"
                + "     {\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\",\"description\":\"来自 body\"}}}}}}"
                + "}}}}"
                + "}";
        ParsedOperation o = only(parse(spec));

        assertEquals(1, o.getRequest().size(), "同名应去重为 1 条");
        ApiParamDto id = find(o.getRequest(), "id");
        assertEquals("int", id.getFieldType(), "保留的应是 body 的定义（integer→int）");
        assertEquals("来自 body", id.getDescription());
    }

    @Test
    @DisplayName("requestParamType 推导：multipart → FORM；form-urlencoded → FORM；纯 query → QUERY")
    void requestParamType_isDerivedFromMediaType() {
        assertEquals("FORM", only(parse(spec("\"/u\":{\"post\":{\"requestBody\":{\"content\":"
                + "{\"multipart/form-data\":{\"schema\":{\"type\":\"object\",\"properties\":"
                + "{\"file\":{\"type\":\"string\"}}}}}}}}", null))).getRequestParamType());

        assertEquals("FORM", only(parse(spec("\"/u\":{\"post\":{\"requestBody\":{\"content\":"
                + "{\"application/x-www-form-urlencoded\":{\"schema\":{\"type\":\"object\",\"properties\":"
                + "{\"a\":{\"type\":\"string\"}}}}}}}}", null))).getRequestParamType());

        assertEquals("QUERY", only(parse(spec("\"/u\":{\"get\":{\"parameters\":"
                + "[{\"name\":\"q\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}]}}", null)))
                .getRequestParamType());
    }

    // =====================================================================
    // $ref / allOf / 类型映射
    // =====================================================================

    @Test
    @DisplayName("$ref 指向 components.schemas 时展开出字段（本仓无 swagger-parser，靠手写解析）")
    void refToComponentSchema_isExpanded() {
        String components = "{\"schemas\":{\"Order\":{\"type\":\"object\","
                + "\"required\":[\"orderNo\"],"
                + "\"properties\":{\"orderNo\":{\"type\":\"string\"},\"amount\":{\"type\":\"number\"}}}}}";
        String paths = "\"/orders\":{\"post\":{\"requestBody\":{\"required\":true,\"content\":"
                + "{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/Order\"}}}}}}";

        ParsedOperation o = only(parse(spec(paths, components)));
        assertEquals(2, o.getRequest().size());
        assertEquals(1, find(o.getRequest(), "orderNo").getRequired());
        assertEquals(0, find(o.getRequest(), "amount").getRequired());
        assertEquals("number", find(o.getRequest(), "amount").getFieldType());
    }

    @Test
    @DisplayName("allOf 合并多分支（含 $ref 分支）的 properties 与 required")
    void allOf_mergesBranches() {
        String components = "{\"schemas\":{"
                + "\"Base\":{\"type\":\"object\",\"required\":[\"id\"],\"properties\":{\"id\":{\"type\":\"string\"}}},"
                + "\"Page\":{\"type\":\"object\",\"properties\":{\"size\":{\"type\":\"integer\"}}}"
                + "}}";
        String paths = "\"/orders\":{\"post\":{\"requestBody\":{\"required\":true,\"content\":"
                + "{\"application/json\":{\"schema\":{\"allOf\":["
                + "  {\"$ref\":\"#/components/schemas/Base\"},"
                + "  {\"$ref\":\"#/components/schemas/Page\"}"
                + "]}}}}}}";

        ParsedOperation o = only(parse(spec(paths, components)));
        assertEquals(2, o.getRequest().size(), "两个分支的字段应合并");
        assertNotNull(find(o.getRequest(), "id"));
        assertNotNull(find(o.getRequest(), "size"));
    }

    @Test
    @DisplayName("嵌套 object 展成 parent.child 点号字段；递归上限内展开")
    void nestedObject_isFlattenedWithDotNotation() {
        String paths = "\"/u\":{\"post\":{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":"
                + "{\"type\":\"object\",\"properties\":{"
                + "  \"user\":{\"type\":\"object\",\"properties\":{"
                + "     \"name\":{\"type\":\"string\"},"
                + "     \"addr\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"
                + "  }}}}}}}}}}";

        ParsedOperation o = only(parse(spec(paths, null)));
        List<String> names = o.getRequest().stream().map(ApiParamDto::getFieldName).collect(java.util.stream.Collectors.toList());
        assertTrue(names.contains("user.name"), "二级字段应带点号：" + names);
        assertTrue(names.contains("user.addr.city"), "三级字段应带点号：" + names);
        assertFalse(names.contains("user"), "有 properties 的节点本身不产生行（否则参数页会多出无意义的父行）");
    }

    @Test
    @DisplayName("自引用 $ref 不死循环（Node.next → Node）")
    void selfReferencingRef_terminates() {
        String components = "{\"schemas\":{\"Node\":{\"type\":\"object\",\"properties\":{"
                + "\"value\":{\"type\":\"string\"},"
                + "\"next\":{\"$ref\":\"#/components/schemas/Node\"}}}}}";
        String paths = "\"/n\":{\"post\":{\"requestBody\":{\"content\":{\"application/json\":"
                + "{\"schema\":{\"$ref\":\"#/components/schemas/Node\"}}}}}}";

        ParsedOperation o = only(parse(spec(paths, components)));
        assertNotNull(find(o.getRequest(), "value"), "循环之外的字段仍应正常展开");
        // 深度上限 MAX_DEPTH=3 ⇒ 展开深度有限：value / next.value / next.next.value /
        // next.next.next.value / next.next.next.next（最深一层不再递归，只记节点本身）。
        // 断言上界而非精确值：本用例的目的是「不死循环」，不是锁死层数。
        assertTrue(o.getRequest().size() <= 6, "循环应在深度上限处收敛，实际条数：" + o.getRequest().size());
        for (ApiParamDto d : o.getRequest()) {
            int dots = d.getFieldName().length() - d.getFieldName().replace(".", "").length();
            assertTrue(dots <= 4, "字段名层级不得无限增长：" + d.getFieldName());
        }
    }

    @Test
    @DisplayName("fieldType 口径：integer→int、boolean→bool、array→array、无 type 有 properties→object")
    void fieldType_usesConsoleVocabulary() {
        String paths = "\"/u\":{\"post\":{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":"
                + "{\"type\":\"object\",\"properties\":{"
                + "  \"i\":{\"type\":\"integer\"},"
                + "  \"b\":{\"type\":\"boolean\"},"
                + "  \"arr\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}},"
                + "  \"o\":{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}},"
                + "  \"s\":{\"type\":\"string\"}}}}}}}}}";

        ParsedOperation o = only(parse(spec(paths, null)));
        assertEquals("int", find(o.getRequest(), "i").getFieldType());
        assertEquals("bool", find(o.getRequest(), "b").getFieldType());
        assertEquals("array", find(o.getRequest(), "arr").getFieldType());
        assertEquals("string", find(o.getRequest(), "o.x").getFieldType(), "object 自身展开，类型落在子字段上");
        assertEquals("string", find(o.getRequest(), "s").getFieldType());
        assertTrue(find(o.getRequest(), "arr").getDescription().contains("数组元素: int"),
                "数组元素类型补进描述：" + find(o.getRequest(), "arr").getDescription());
    }

    @Test
    @DisplayName("枚举取值补进描述（api_param 没有对应列，丢了可惜）")
    void enumValues_areAppendedToDescription() {
        String paths = "\"/u\":{\"get\":{\"parameters\":[{\"name\":\"status\",\"in\":\"query\","
                + "\"description\":\"状态\",\"schema\":{\"type\":\"string\",\"enum\":[\"A\",\"B\"]}}]}}";

        ApiParamDto d = only(parse(spec(paths, null))).getRequest().get(0);
        assertTrue(d.getDescription().contains("枚举: A/B"), d.getDescription());
        assertEquals("A", d.getExample(), "枚举首值作为示例值兜底");
    }

    @Test
    @DisplayName("示例值优先级：example → default → 枚举首个")
    void example_priority() {
        String paths = "\"/u\":{\"post\":{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":"
                + "{\"type\":\"object\",\"properties\":{"
                + "  \"a\":{\"type\":\"string\",\"example\":\"ex\"},"
                + "  \"b\":{\"type\":\"string\",\"default\":\"dv\"},"
                + "  \"c\":{\"type\":\"string\",\"enum\":[\"e1\",\"e2\"]},"
                + "  \"d\":{\"type\":\"string\"}}}}}}}}}";

        ParsedOperation o = only(parse(spec(paths, null)));
        assertEquals("ex", find(o.getRequest(), "a").getExample());
        assertEquals("dv", find(o.getRequest(), "b").getExample());
        assertEquals("e1", find(o.getRequest(), "c").getExample());
        assertNull(find(o.getRequest(), "d").getExample(), "都没有则为 null，而不是空串");
    }

    // =====================================================================
    // 响应分区
    // =====================================================================

    @Test
    @DisplayName("2xx 带结构 → Response(4)；4xx/5xx/default → Error(5)")
    void responses_areSplitBetweenResponseAndError() {
        String paths = "\"/u\":{\"get\":{\"responses\":{"
                + "  \"200\":{\"description\":\"ok\",\"content\":{\"application/json\":{\"schema\":"
                + "     {\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"integer\"}}}}}},"
                + "  \"204\":{\"description\":\"无内容\"},"
                + "  \"400\":{\"description\":\"参数错误\"},"
                + "  \"500\":{\"description\":\"服务器错误\"},"
                + "  \"default\":{\"description\":\"兜底\"}"
                + "}}}";

        ParsedOperation o = only(parse(spec(paths, null)));
        assertEquals(1, o.getResponse().size(), "204 只有描述没有结构，不产生出参行");
        assertEquals("code", o.getResponse().get(0).getFieldName());
        assertEquals(Integer.valueOf(200), o.getResponse().get(0).getHttpStatus());

        assertEquals(3, o.getError().size(), "400/500/default 都进 Error 分区");
        ApiParamDto e400 = find(o.getError(), "400");
        assertNotNull(e400);
        assertEquals("400", e400.getErrorCode(), "error_code 与 field_name 同写响应码");
        assertEquals(Integer.valueOf(400), e400.getHttpStatus());
        assertEquals("参数错误", e400.getDescription());
        assertNull(find(o.getError(), "default").getHttpStatus(), "非数字响应码的 httpStatus 为 null");
    }

    // =====================================================================
    // 长度与规模保护（上限均按「T17 加密后仍塞得进列宽」反推）
    // =====================================================================

    @Test
    @DisplayName("超长路径被截断到 300 字符（interface_path varchar(512) 且会被加密）")
    void oversizePath_isTruncated() {
        StringBuilder p = new StringBuilder("/o");
        for (int i = 0; i < 400; i++) {
            p.append("/seg");
        }
        ParsedOperation o = only(parse(spec("\"" + p + "\":{\"get\":{}}", null)));
        assertEquals(300, o.getPath().length());
    }

    @Test
    @DisplayName("超长 name / description 截断到界面口径（64 / 200）")
    void oversizeNameAndDescription_areTruncated() {
        String longName = repeat('n', 200);
        String longDesc = repeat('d', 500);
        ParsedOperation o = only(parse(spec("\"/u\":{\"get\":{\"summary\":\"" + longName + "\","
                + "\"description\":\"" + longDesc + "\"}}", null)));
        assertEquals(64, o.getName().length());
        assertEquals(200, o.getDescription().length());
    }

    @Test
    @DisplayName("单分区超过 200 条参数时截断，并留下可解释的 warning")
    void tooManyParamsPerPartition_isTruncatedWithWarning() {
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < 205; i++) {
            if (i > 0) {
                params.append(',');
            }
            params.append("{\"name\":\"p").append(i).append("\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}}");
        }
        ParsedOperation o = only(parse(spec("\"/u\":{\"get\":{\"parameters\":[" + params + "]}}", null)));

        assertEquals(200, o.getRequest().size(), "分区上限 200");
        assertFalse(o.getWarnings().isEmpty(), "截断必须留下提示，否则用户以为文档只有 200 个参数");
        assertTrue(o.getWarnings().get(0).contains("截断"), o.getWarnings().get(0));
    }

    @Test
    @DisplayName("只有描述、没有属性的 body 不报错，但会留下 warning")
    void bodyWithoutProperties_warnsInsteadOfFailing() {
        String paths = "\"/u\":{\"post\":{\"requestBody\":{\"description\":\"整体是一个明文串\","
                + "\"content\":{\"text/plain\":{\"schema\":{\"type\":\"string\"}}}}}}";

        ParsedOperation o = only(parse(spec(paths, null)));
        assertEquals(0, o.paramCount());
        assertFalse(o.getWarnings().isEmpty(), "没有可展开属性时应提示，而不是静默导入 0 个参数");
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
