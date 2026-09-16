package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.dto.InterfaceImportRequest;
import com.gatekeeper.dto.InterfaceImportResult;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.openapi.OpenApiParser;
import com.gatekeeper.openapi.ParsedOperation;
import com.gatekeeper.service.InterfaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OpenAPI 导入编排单测（T18）。
 *
 * <p>被测对象是 {@link InterfaceImportServiceImpl} 的<b>编排语义</b>：
 * 什么情况整批 400、什么情况单条失败、什么情况计入跳过、计数是否对得上。
 * 解析器的映射细节在 {@code OpenApiParserTest}，真实落库在端到端探针。</p>
 *
 * <p><b>需求硬约束</b>：「导入时必须先选择分组」。故这里第一条用例就锁住
 * 「groupId 为 null ⇒ 400，且<b>连解析都不做</b>」—— 顺序也很重要：先拦参数、
 * 再读文档。反过来的话，一份 8MB 的文档会先被完整解析一遍才告诉用户"你没选分组"。</p>
 *
 * <p><b>部分成功语义</b>：单条失败不影响其余（每条独立事务，见
 * {@link InterfaceImportWriter}），故用例里专门验证「一条抛异常后，后面的仍然被写入」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OpenAPI 导入编排（groupId 必填 / 部分成功 / 去重）")
class InterfaceImportServiceImplTest {

    @Mock
    private OpenApiParser openApiParser;
    @Mock
    private InterfaceImportWriter writer;
    @Mock
    private InterfaceService interfaceService;
    @Mock
    private ApiGroupMapper apiGroupMapper;

    private InterfaceImportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new InterfaceImportServiceImpl(openApiParser, writer, interfaceService, apiGroupMapper);
    }

    // =====================================================================
    // 基础设施
    // =====================================================================

    private static ApiGroup group(long id, String name) {
        ApiGroup g = new ApiGroup();
        g.setId(id);
        g.setGroupName(name);
        return g;
    }

    private static InterfaceImportRequest req(Long groupId, String content) {
        InterfaceImportRequest r = new InterfaceImportRequest();
        r.setGroupId(groupId);
        r.setContent(content);
        r.setFileName("api.json");
        return r;
    }

    /** 造一个解析产物：数量可控、路径可控。 */
    private static ParsedOperation op(String method, String path) {
        ParsedOperation po = new ParsedOperation();
        po.setMethod(method);
        po.setPath(path);
        po.setName(method + " " + path);
        po.setRequestParamType("JSON");
        po.setSourceLocation("paths." + path + "." + method.toLowerCase());
        ApiParamDto p = new ApiParamDto();
        p.setFieldName("id");
        p.setFieldType("string");
        p.setRequired(0);
        po.getRequest().add(p);
        return po;
    }

    private static OpenApiParser.Spec specOf(ParsedOperation... ops) {
        OpenApiParser.Spec s = new OpenApiParser.Spec();
        s.setOpenapiVersion("3.0.1");
        s.setTitle("订单服务");
        s.setVersion("1.0.0");
        s.setServerUrl("https://gw.example.com/api");
        s.setOperations(new ArrayList<>(Arrays.asList(ops)));
        return s;
    }

    /** 默认：分组存在、无编码冲突、解析出 1 个 operation、写入成功。 */
    private void stubHappyPath(ParsedOperation... ops) {
        when(apiGroupMapper.selectById(anyLong())).thenReturn(group(7L, "订单分组"));
        when(openApiParser.parse(any(byte[].class), any())).thenReturn(specOf(ops));
        when(interfaceService.count(any(Wrapper.class))).thenReturn(0L);
    }

    private void stubWriteOk(long apiId, int params) {
        when(writer.writeOne(any(ParsedOperation.class), anyLong(), anyString(), any(), any()))
                .thenReturn(new InterfaceImportWriter.WriteOutcome(apiId, params));
    }

    // =====================================================================
    // 整批拒绝
    // =====================================================================

    @Test
    @DisplayName("未选分组 ⇒ 400，且完全不去解析文档（先拦参数、再读文档）")
    void missingGroupId_rejectsBeforeParsing() {
        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.importSpec(req(null, "{\"openapi\":\"3.0.1\"}"), 1L, "admin"));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("请先选择分组"), ex.getMessage());
        assertTrue(ex.getMessage().contains("必须"), "提示要说清这是硬性要求：" + ex.getMessage());
        verifyNoInteractions(openApiParser);
        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("分组不存在 ⇒ 400（防落一个悬空 group_id）")
    void unknownGroup_rejected() {
        when(apiGroupMapper.selectById(99L)).thenReturn(null);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.importSpec(req(99L, "x"), 1L, "admin"));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("所属分组不存在"), ex.getMessage());
        verifyNoInteractions(openApiParser);
    }

    @Test
    @DisplayName("内容为空 ⇒ 400（含全空白）")
    void blankContent_rejected() {
        when(apiGroupMapper.selectById(anyLong())).thenReturn(group(7L, "g"));

        assertEquals(400, assertThrows(GatewayException.class,
                () -> service.importSpec(req(7L, null), 1L, "admin")).getCode());
        assertEquals(400, assertThrows(GatewayException.class,
                () -> service.importSpec(req(7L, "   "), 1L, "admin")).getCode());
        verifyNoInteractions(openApiParser);
    }

    @Test
    @DisplayName("解析失败 ⇒ 原样透传该异常（整批 400，一条都不写）")
    void parseFailure_propagatesAndWritesNothing() {
        when(apiGroupMapper.selectById(anyLong())).thenReturn(group(7L, "g"));
        GatewayException parseErr = GatewayException.badRequest("检测到 Swagger 2.0 文档");
        when(openApiParser.parse(any(byte[].class), any())).thenThrow(parseErr);

        GatewayException ex = assertThrows(GatewayException.class,
                () -> service.importSpec(req(7L, "{\"swagger\":\"2.0\"}"), 1L, "admin"));

        assertSame(parseErr, ex, "应原样透传，不要重新包装丢失原始信息");
        verifyNoInteractions(writer);
    }

    // =====================================================================
    // 正常导入
    // =====================================================================

    @Test
    @DisplayName("两条 operation 全部成功：计数、参数累计、明细逐条就位")
    void happyPath_countsAndItems() {
        stubHappyPath(op("GET", "/users"), op("POST", "/orders"));
        when(interfaceService.count(any(Wrapper.class))).thenReturn(0L);
        when(writer.writeOne(any(ParsedOperation.class), anyLong(), anyString(), any(), any()))
                .thenReturn(new InterfaceImportWriter.WriteOutcome(11L, 3))
                .thenReturn(new InterfaceImportWriter.WriteOutcome(12L, 2));

        InterfaceImportResult r = service.importSpec(req(7L, "doc"), 42L, "alice");

        assertEquals(2, r.getTotal());
        assertEquals(2, r.getImported());
        assertEquals(0, r.getSkipped());
        assertEquals(0, r.getFailed());
        assertEquals(5, r.getParamCount(), "参数条数应跨接口累计（3 + 2）");
        assertEquals(2, r.getItems().size());

        InterfaceImportResult.Item first = r.getItems().get(0);
        assertEquals(InterfaceImportResult.Item.IMPORTED, first.getResult());
        assertEquals(Long.valueOf(11L), first.getApiId());
        assertEquals(3, first.getParamCount());
        assertEquals("GET", first.getMethod());
        assertEquals("/users", first.getPath());
        assertEquals("paths./users.get", first.getSource());

        // 分组与文档元信息回显，便于用户确认"导对地方了"
        assertEquals(Long.valueOf(7L), r.getGroupId());
        assertEquals("订单分组", r.getGroupName());
        assertEquals("3.0.1", r.getOpenapiVersion());
        assertEquals("订单服务", r.getTitle());
        assertEquals("1.0.0", r.getSpecVersion());
        assertEquals("https://gw.example.com/api", r.getServerUrl());
    }

    @Test
    @DisplayName("操作人信息透传给写入器（落 api_interface.owner_id / owner_name）")
    void operator_isPassedToWriter() {
        stubHappyPath(op("GET", "/users"));
        stubWriteOk(11L, 1);

        service.importSpec(req(7L, "doc"), 42L, "alice");

        verify(writer).writeOne(any(ParsedOperation.class), eq(7L), anyString(), eq(42L), eq("alice"));
    }

    @Test
    @DisplayName("接口编码由「方法 + 路径」确定性推导（同一文档重复导入才能被识别为同一接口）")
    void apiCode_isDeterministic() {
        stubHappyPath(op("GET", "/users/{id}"));
        stubWriteOk(11L, 1);

        service.importSpec(req(7L, "doc"), 1L, "admin");
        String first = captorApiCode();

        // 第二次导入同一份文档：编码必须与第一次完全一致，否则去重形同虚设
        service.importSpec(req(7L, "doc"), 1L, "admin");
        assertEquals(first, lastApiCode());
    }

    @Test
    @DisplayName("超长路径的接口编码截到 64 字符，且前缀相同的不同接口不会撞码")
    void longPath_apiCodeIsShortenedButStillUnique() {
        StringBuilder base = new StringBuilder("/o");
        for (int i = 0; i < 80; i++) {
            base.append("/segment");
        }
        // 两条只在末尾不同的长路径：若实现是"简单截断"，它们会得到同一个 api_code，
        // 第二个就会被误判成"已导入"而静默丢掉 —— 故必须靠内容哈希拉开。
        stubHappyPath(op("GET", base + "/alpha"), op("GET", base + "/beta"));
        stubWriteOk(11L, 1);

        service.importSpec(req(7L, "doc"), 1L, "admin");

        List<String> codes = capturedApiCodes();
        assertEquals(2, codes.size());
        assertTrue(codes.get(0).length() <= 64, "编码必须塞得进 api_code varchar(64)：" + codes.get(0).length());
        assertTrue(codes.get(1).length() <= 64);
        assertTrue(!codes.get(0).equals(codes.get(1)),
                "前缀相同的长路径不得撞成同一编码：" + codes);
    }

    // =====================================================================
    // 去重（跳过）与部分失败
    // =====================================================================

    @Test
    @DisplayName("接口编码已存在 ⇒ 计入跳过，且不再调用写入器")
    void existingApiCode_isSkipped() {
        stubHappyPath(op("GET", "/users"));
        when(interfaceService.count(any(Wrapper.class))).thenReturn(1L);

        InterfaceImportResult r = service.importSpec(req(7L, "doc"), 1L, "admin");

        assertEquals(1, r.getSkipped());
        assertEquals(0, r.getImported());
        assertEquals(0, r.getParamCount());
        InterfaceImportResult.Item item = r.getItems().get(0);
        assertEquals(InterfaceImportResult.Item.SKIPPED, item.getResult());
        assertTrue(item.getMessage().contains("已存在"), item.getMessage());
        verify(writer, never()).writeOne(any(), anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("并发下的唯一键冲突同样归为跳过（不是失败）")
    void duplicateKey_isTreatedAsSkipped() {
        stubHappyPath(op("GET", "/users"));
        when(interfaceService.count(any(Wrapper.class))).thenReturn(0L);
        when(writer.writeOne(any(ParsedOperation.class), anyLong(), anyString(), any(), any()))
                .thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_iface_code'"));

        InterfaceImportResult r = service.importSpec(req(7L, "doc"), 1L, "admin");

        assertEquals(0, r.getFailed(), "唯一键冲突是「已存在」语义，不该记成失败");
        assertEquals(1, r.getSkipped());
        assertEquals(InterfaceImportResult.Item.SKIPPED, r.getItems().get(0).getResult());
    }

    @Test
    @DisplayName("单条失败不影响其余：一条抛异常，后面的仍然写入")
    void oneFailure_doesNotAbortTheRest() {
        stubHappyPath(op("GET", "/a"), op("GET", "/b"), op("GET", "/c"));
        when(interfaceService.count(any(Wrapper.class))).thenReturn(0L);
        when(writer.writeOne(any(ParsedOperation.class), anyLong(), anyString(), any(), any()))
                .thenReturn(new InterfaceImportWriter.WriteOutcome(1L, 2))
                .thenThrow(new RuntimeException("包装异常", new IllegalStateException("Column 'interface_path' too long")))
                .thenReturn(new InterfaceImportWriter.WriteOutcome(3L, 4));

        InterfaceImportResult r = service.importSpec(req(7L, "doc"), 1L, "admin");

        assertEquals(3, r.getTotal());
        assertEquals(2, r.getImported(), "第 1、3 条成功");
        assertEquals(1, r.getFailed(), "第 2 条失败");
        assertEquals(6, r.getParamCount(), "只累计成功写入的参数（2 + 4）");
        assertEquals(3, r.getItems().size(), "失败的也要出现在明细里，否则用户不知道少了哪条");

        InterfaceImportResult.Item failed = r.getItems().get(1);
        assertEquals(InterfaceImportResult.Item.FAILED, failed.getResult());
        assertTrue(failed.getMessage().contains("too long"),
                "应取最内层异常消息（只有它可照做）：" + failed.getMessage());
    }

    @Test
    @DisplayName("解析期提示原样带进明细（如分区被截断），且不与解析产物共享列表引用")
    void parseWarnings_areCarriedIntoItems() {
        ParsedOperation po = op("GET", "/users");
        po.getWarnings().add("分区 3 参数超过 200 条，已截断");
        stubHappyPath(po);
        stubWriteOk(11L, 1);

        InterfaceImportResult r = service.importSpec(req(7L, "doc"), 1L, "admin");

        assertEquals(1, r.getItems().get(0).getWarnings().size());
        assertEquals("分区 3 参数超过 200 条，已截断", r.getItems().get(0).getWarnings().get(0));
        // 明细里的 list 必须是副本：否则后续对 item 的加工会反向污染解析产物
        r.getItems().get(0).getWarnings().add("额外");
        assertEquals(1, po.getWarnings().size(), "不应共享引用");
    }

    // =====================================================================
    // 全局提示：把「导入之后还需要人做什么」说清楚
    // =====================================================================

    @Test
    @DisplayName("结果里说明后续待办：草稿态 + 后端地址留空 + 不建版本 + servers 仅供参考")
    void globalWarnings_explainFollowUp() {
        stubHappyPath(op("GET", "/users"));
        stubWriteOk(11L, 1);

        InterfaceImportResult r = service.importSpec(req(7L, "doc"), 1L, "admin");
        String all = String.join("\n", r.getWarnings());

        assertTrue(all.contains("草稿"), "要说清导入后是草稿态：" + all);
        assertTrue(all.contains("后端"), "要说清后端地址需要人补：" + all);
        assertTrue(all.contains("版本"), "要说清不创建版本记录：" + all);
        assertTrue(all.contains("https://gw.example.com/api"), "回显 servers 供参考：" + all);
        assertTrue(all.contains("回环"), "要解释为什么不自动写后端地址：" + all);
    }

    @Test
    @DisplayName("有跳过时，全局提示里给出跳过条数与原因")
    void globalWarnings_mentionSkippedCount() {
        stubHappyPath(op("GET", "/users"), op("GET", "/orders"));
        when(interfaceService.count(any(Wrapper.class))).thenReturn(1L);

        InterfaceImportResult r = service.importSpec(req(7L, "doc"), 1L, "admin");

        assertEquals(2, r.getSkipped());
        assertTrue(String.join("\n", r.getWarnings()).contains("2 条"), r.getWarnings().toString());
    }

    // =====================================================================
    // 捕获工具
    // =====================================================================

    private List<String> capturedApiCodes() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(writer, org.mockito.Mockito.atLeastOnce())
                .writeOne(any(ParsedOperation.class), anyLong(), cap.capture(), any(), any());
        return new ArrayList<>(cap.getAllValues());
    }

    private String captorApiCode() {
        return capturedApiCodes().get(0);
    }

    /** 最后一次写入用的 api_code。 */
    private String lastApiCode() {
        List<String> all = capturedApiCodes();
        return all.get(all.size() - 1);
    }
}
