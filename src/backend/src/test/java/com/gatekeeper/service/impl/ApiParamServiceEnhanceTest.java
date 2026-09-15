package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.ApiParamBatchSaveRequest;
import com.gatekeeper.dto.ApiParamBatchSaveResult;
import com.gatekeeper.dto.ApiParamCheckResult;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.dto.ApiParamImportResult;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiParamMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApiParamServiceImpl T03b 增强能力单测 —— 批量保存 / JSON 导入 / 必填校验。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiParamService 批量保存/导入/校验")
class ApiParamServiceEnhanceTest {

    @Mock
    private ApiParamMapper apiParamMapper;

    /** T17：新增的两个协作对象（加解密 + 可见性） */
    @Mock
    private com.gatekeeper.mapper.ApiInterfaceMapper apiInterfaceMapper;
    @Mock
    private com.gatekeeper.service.InterfaceVisibilityService interfaceVisibilityService;

    private ApiParamServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ApiParamServiceImpl(new com.gatekeeper.support.PassthroughInterfaceCrypto(),
                interfaceVisibilityService, apiInterfaceMapper);
        when(interfaceVisibilityService.resolveViewer())
                .thenReturn(com.gatekeeper.security.InterfaceViewer.unprotected());
        Field f = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class.getDeclaredField("baseMapper");
        f.setAccessible(true);
        f.set(service, apiParamMapper);
    }

    private ApiParamDto param(String name, String type, int required) {
        ApiParamDto d = new ApiParamDto();
        d.setFieldName(name);
        d.setFieldType(type);
        d.setRequired(required);
        return d;
    }

    @Test
    @DisplayName("batchSave 对提交的非空分区逐一先删后插")
    void batchSave_replacesProvidedSections() {
        ApiParamBatchSaveRequest req = new ApiParamBatchSaveRequest();
        req.setApiId(1L);
        req.setHeader(Collections.singletonList(param("Content-Type", "string", 1)));
        req.setRequest(Arrays.asList(param("outOrderNo", "string", 1), param("userId", "int", 1)));
        req.setResponse(Collections.singletonList(param("orderNo", "string", 1)));
        req.setError(Collections.singletonList(param("ORDER_DUPLICATE", "string", 0)));

        when(apiParamMapper.insert(any(ApiParam.class))).thenReturn(1);

        ApiParamBatchSaveResult r = service.batchSave(req);
        assertEquals(1, r.getHeader());
        assertEquals(2, r.getRequest());
        assertEquals(1, r.getResponse());
        assertEquals(1, r.getError());
        assertEquals(5, r.getTotal());
        verify(apiParamMapper, times(4)).delete(any(QueryWrapper.class));
        verify(apiParamMapper, times(5)).insert(any(ApiParam.class));
    }

    @Test
    @DisplayName("batchSave 未提交的分区(null)保持不变，不出现在结果统计")
    void batchSave_nullSectionUnchanged() {
        ApiParamBatchSaveRequest req = new ApiParamBatchSaveRequest();
        req.setApiId(1L);
        req.setRequest(Collections.singletonList(param("a", "string", 0)));
        when(apiParamMapper.insert(any(ApiParam.class))).thenReturn(1);

        ApiParamBatchSaveResult r = service.batchSave(req);
        assertEquals(-1, r.getHeader());
        assertEquals(1, r.getRequest());
        // 只对 request 分区 delete
        verify(apiParamMapper, times(1)).delete(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("batchSave 缺 apiId 抛 400")
    void batchSave_missingApiId() {
        ApiParamBatchSaveRequest req = new ApiParamBatchSaveRequest();
        GatewayException ex = assertThrows(GatewayException.class, () -> service.batchSave(req));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("batchSave 某条 fieldName 为空抛 400")
    void batchSave_blankFieldName() {
        ApiParamBatchSaveRequest req = new ApiParamBatchSaveRequest();
        req.setApiId(1L);
        req.setRequest(Collections.singletonList(param("", "string", 1)));
        GatewayException ex = assertThrows(GatewayException.class, () -> service.batchSave(req));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("importParams 校验失败：返回失败明细且不落库")
    void import_rejectsInvalidItems() {
        ApiParamBatchSaveRequest req = new ApiParamBatchSaveRequest();
        req.setApiId(1L);
        ApiParamDto bad = param("", "string", 1); // fieldName 空
        req.setRequest(Collections.singletonList(bad));
        ApiParamDto err = param("OPS", "string", 0); // error 分区无 errorCode
        req.setError(Collections.singletonList(err));

        ApiParamImportResult r = service.importParams(req);
        assertFalse(r.isSuccess());
        assertEquals(0, r.getImported());
        assertEquals(2, r.getErrors().size());
        // 关键：不落库
        verify(apiParamMapper, times(0)).insert(any(ApiParam.class));
        verify(apiParamMapper, times(0)).delete(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("importParams 全部通过：落库并返回导入条数")
    void import_commitsWhenAllValid() {
        ApiParamBatchSaveRequest req = new ApiParamBatchSaveRequest();
        req.setApiId(1L);
        req.setRequest(Arrays.asList(param("a", "string", 1), param("b", "int", 0)));
        ApiParamDto err = param("E1", "string", 0);
        err.setErrorCode("E1");
        req.setError(Collections.singletonList(err));
        when(apiParamMapper.insert(any(ApiParam.class))).thenReturn(1);

        ApiParamImportResult r = service.importParams(req);
        assertTrue(r.isSuccess());
        assertEquals(3, r.getImported());
        assertTrue(r.getErrors().isEmpty());
        verify(apiParamMapper, times(3)).insert(any(ApiParam.class));
    }

    @Test
    @DisplayName("importTemplate 返回含四个分区的模板")
    void importTemplate_hasAllSections() {
        String tpl = service.importTemplate();
        assertNotNull(tpl);
        assertTrue(tpl.contains("\"header\""));
        assertTrue(tpl.contains("\"request\""));
        assertTrue(tpl.contains("\"response\""));
        assertTrue(tpl.contains("\"error\""));
    }

    @Test
    @DisplayName("checkRequired：必填缺 fieldType / 错误码缺 errorCode / 无入参 → 不通过")
    void checkRequired_detectsIssues() {
        List<ApiParam> rows = new ArrayList<>();
        ApiParam p1 = new ApiParam();
        p1.setId(1L);
        p1.setParamType(4); // response
        p1.setRequired(1);
        p1.setFieldName("orderNo");
        p1.setFieldType(null); // 必填缺类型
        rows.add(p1);
        ApiParam p2 = new ApiParam();
        p2.setId(2L);
        p2.setParamType(5); // error 无 errorCode
        p2.setRequired(0);
        p2.setFieldName("E");
        p2.setFieldType("string");
        rows.add(p2);
        when(apiParamMapper.selectList(any(QueryWrapper.class))).thenReturn(rows);

        ApiParamCheckResult r = service.checkRequired(1L);
        assertFalse(r.isPassed());
        assertEquals(2, r.getTotal());
        assertEquals(1, r.getRequiredCount());
        // 三条问题：缺 fieldType + 缺 errorCode + 无入参
        assertEquals(3, r.getIssues().size());
    }

    @Test
    @DisplayName("checkRequired：齐全时通过")
    void checkRequired_passesWhenComplete() {
        List<ApiParam> rows = new ArrayList<>();
        ApiParam p = new ApiParam();
        p.setId(1L);
        p.setParamType(3);
        p.setRequired(1);
        p.setFieldName("outOrderNo");
        p.setFieldType("string");
        rows.add(p);
        when(apiParamMapper.selectList(any(QueryWrapper.class))).thenReturn(rows);

        ApiParamCheckResult r = service.checkRequired(1L);
        assertTrue(r.isPassed());
        assertEquals(0, r.getIssues().size());
    }
}
