package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.crypto.CryptoServiceImpl;
import com.gatekeeper.crypto.InterfaceCryptoService;
import com.gatekeeper.crypto.InterfaceCryptoServiceImpl;
import com.gatekeeper.dto.ApiParamBatchSaveRequest;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.dto.InterfaceListVo;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiChangeLogMapper;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.ApiParamMapper;
import com.gatekeeper.mapper.ApiVersionMapper;
import com.gatekeeper.security.InterfaceViewer;
import com.gatekeeper.service.InterfaceVisibilityService;
import com.gatekeeper.service.SysInterfaceCryptoConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>掩码回写防线</b>单测 — T17。
 *
 * <p>这是本特性里<b>最容易造成不可逆数据损毁</b>的一族缺陷，专列一个测试类守住。</p>
 *
 * <h3>缺陷场景（若不设防会真实发生）</h3>
 * <p>不在可见性白名单内的用户打开接口详情/编辑弹窗时，他看到的是掩码
 * {@code ****}。他「什么都不改，直接点保存」时前端会把 {@code ****} 原样提交。后端若照单全收：</p>
 * <ul>
 *   <li><b>单条 update</b>：{@code interface_path} 被写成 {@code ****} ⇒ 网关对这条接口
 *       <b>永久 404</b>，且没有任何报错；参数三列同理被写坏。</li>
 *   <li><b>全量替换 batchSave</b>（先删后插）：更严重 —— 原值随「先删」一起消失，
 *       {@code ****} 落库后<b>不可逆</b>，无法回滚。</li>
 * </ul>
 *
 * <h3>三道防线（本类逐一验证）</h3>
 * <ol>
 *   <li>{@code ApiParamServiceImpl.update}：掩码列跳过赋值（保持库中原密文）；</li>
 *   <li>{@code ApiParamServiceImpl.create}：掩码 fieldName ⇒ 400 拒绝；</li>
 *   <li>{@code ApiParamServiceImpl.replaceSection}：批次内出现掩码 ⇒ 400 且
 *       <b>先删与后插都不执行</b>；</li>
 *   <li>{@code InterfaceServiceImpl.applyPathWriteBackGuard}：掩码/空 ⇒ 用库中原值
 *       回填，本次 update 对该列成为 no-op。</li>
 * </ol>
 *
 * <p>用<b>真的</b> {@link InterfaceCryptoServiceImpl}（而非 Passthrough 替身），
 * 这样"掩码有没有被写坏真值"才是真的在密文上验证过。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("接口信息掩码回写防线 T17")
class InterfaceInfoMaskGuardTest {

    private static final String AES_KEY = "TEST_32_BYTES_LONG_KEY_FOR_AES_256";
    private static final String MASK = InterfaceCryptoService.MASK;

    // ---- 参数侧 ----
    @Mock
    private ApiParamMapper apiParamMapper;
    @Mock
    private ApiInterfaceMapper apiInterfaceMapper;
    @Mock
    private InterfaceVisibilityService interfaceVisibilityService;

    // ---- 接口侧 ----
    @Mock
    private ApiGroupMapper apiGroupMapper;
    @Mock
    private ApiVersionMapper apiVersionMapper;
    @Mock
    private ApiEnvConfigMapper apiEnvConfigMapper;
    @Mock
    private ApiChangeLogMapper apiChangeLogMapper;

    @Mock
    private SysInterfaceCryptoConfigService configService;

    private InterfaceCryptoService crypto;
    private ApiParamServiceImpl paramService;
    private InterfaceServiceImpl interfaceService;

    @BeforeEach
    void setUp() {
        crypto = new InterfaceCryptoServiceImpl(new CryptoServiceImpl(), configService);
        ReflectionTestUtils.setField(crypto, "aesDbKey", AES_KEY);
        when(configService.isEnabled()).thenReturn(true);

        paramService = new ApiParamServiceImpl(crypto, interfaceVisibilityService, apiInterfaceMapper);
        interfaceService = new InterfaceServiceImpl(
                apiGroupMapper, apiParamMapper, apiVersionMapper, apiEnvConfigMapper,
                apiChangeLogMapper, crypto, interfaceVisibilityService);

        injectBaseMapper(paramService, apiParamMapper);
        injectBaseMapper(interfaceService, apiInterfaceMapper);

        when(interfaceVisibilityService.resolveViewer())
                .thenReturn(InterfaceViewer.unprotected());
    }

    private static void injectBaseMapper(Object service, Object mapper) {
        try {
            Field f = com.baomidou.mybatisplus.extension.service.impl.ServiceImpl.class
                    .getDeclaredField("baseMapper");
            f.setAccessible(true);
            f.set(service, mapper);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 一个全黑 viewer：保护启用、白名单为空、非超管、uid 为空 ⇒ 一律掩码 */
    private static InterfaceViewer maskedViewer() {
        return InterfaceViewer.failSafeMaskAll();
    }

    // =====================================================================
    // 防线 1：单条 update 跳过掩码列
    // =====================================================================

    @Test
    @DisplayName("🔴 防线1：update 提交全掩码 ⇒ 三列保持库中原密文，绝不被 **** 覆盖")
    void update_allMaskInput_keepsStoredCiphertext() {
        String storedName = crypto.encryptField("skuId");
        String storedExample = crypto.encryptField("1001");
        String storedDesc = crypto.encryptField("商品ID");

        ApiParam existing = new ApiParam();
        existing.setId(1L);
        existing.setApiId(1L);
        existing.setParamType(3);
        existing.setFieldName(storedName);
        existing.setExample(storedExample);
        existing.setDescription(storedDesc);
        when(apiParamMapper.selectById(1L)).thenReturn(existing);

        // 非白名单用户看到的就是掩码，原样回传
        ApiParamDto dto = new ApiParamDto();
        dto.setFieldName(MASK);
        dto.setExample(MASK);
        dto.setDescription(MASK);
        dto.setFieldType("string"); // 结构列是真值，允许改

        paramService.update(1L, dto);

        ArgumentCaptor<ApiParam> cap = ArgumentCaptor.forClass(ApiParam.class);
        verify(apiParamMapper).updateById(cap.capture());
        ApiParam saved = cap.getValue();

        assertEquals(storedName, saved.getFieldName(), "fieldName 被掩码覆盖了");
        assertEquals(storedExample, saved.getExample(), "example 被掩码覆盖了");
        assertEquals(storedDesc, saved.getDescription(), "description 被掩码覆盖了");
        assertNotEquals(MASK, saved.getFieldName());
        // 语义级断言：值仍能正确还原（没有损坏）
        assertEquals("skuId", crypto.decryptField(saved.getFieldName()));
        assertEquals("1001", crypto.decryptField(saved.getExample()));
        assertEquals("商品ID", crypto.decryptField(saved.getDescription()));
    }

    @Test
    @DisplayName("防线1：update 混填（掩码列保留 + 明文字段加密写入）")
    void update_partialMask_maskKeptPlainWritten() {
        String storedName = crypto.encryptField("skuId");
        ApiParam existing = new ApiParam();
        existing.setId(1L);
        existing.setApiId(1L);
        existing.setParamType(3);
        existing.setFieldName(storedName);
        existing.setDescription(crypto.encryptField("商品ID"));
        when(apiParamMapper.selectById(1L)).thenReturn(existing);

        ApiParamDto dto = new ApiParamDto();
        dto.setFieldName(MASK);            // 看不到 ⇒ 保持
        dto.setExample("2002");            // 结构外的明文 ⇒ 加密写入
        dto.setDescription(MASK);          // 看不到 ⇒ 保持

        paramService.update(1L, dto);

        ArgumentCaptor<ApiParam> cap = ArgumentCaptor.forClass(ApiParam.class);
        verify(apiParamMapper).updateById(cap.capture());
        ApiParam saved = cap.getValue();

        assertEquals(storedName, saved.getFieldName());
        assertTrue(crypto.isEncrypted(saved.getExample()), "新提交的明文必须加密落库");
        assertEquals("2002", crypto.decryptField(saved.getExample()));
        assertEquals("商品ID", crypto.decryptField(saved.getDescription()));
    }

    // =====================================================================
    // 防线 2：create 拒绝掩码
    // =====================================================================

    @Test
    @DisplayName("🔴 防线2：create 的 fieldName 为掩码 ⇒ 400 且不落库")
    void create_maskFieldName_rejected() {
        ApiParamDto dto = new ApiParamDto();
        dto.setApiId(1L);
        dto.setParamType(3);
        dto.setFieldName(MASK);

        GatewayException ex = assertThrows(GatewayException.class, () -> paramService.create(dto));
        assertEquals(400, ex.getCode());
        verify(apiParamMapper, never()).insert(any(ApiParam.class));
    }

    // =====================================================================
    // 防线 3：全量替换（先删后插）遇到掩码必须整批拒绝
    // =====================================================================

    @Test
    @DisplayName("🔴 防线3：batchSave 含掩码 ⇒ 400，且『先删』也没执行（否则不可逆）")
    void batchSave_maskInBatch_rejectedBeforeDelete() {
        ApiParamBatchSaveRequest req = new ApiParamBatchSaveRequest();
        req.setApiId(1L);

        List<ApiParamDto> items = new ArrayList<>();
        ApiParamDto ok = new ApiParamDto();
        ok.setFieldName("skuId");
        ok.setParamType(3);
        items.add(ok);
        ApiParamDto masked = new ApiParamDto();
        masked.setFieldName(MASK);   // 提交者看不到真值
        masked.setParamType(3);
        items.add(masked);
        req.setRequest(items);

        GatewayException ex = assertThrows(GatewayException.class, () -> paramService.batchSave(req));
        assertEquals(400, ex.getCode());

        // 关键：先删没有发生 —— 原始数据仍在，用户拿到权限后可重试
        verify(apiParamMapper, never()).delete(any(QueryWrapper.class));
        verify(apiParamMapper, never()).insert(any(ApiParam.class));
    }

    @Test
    @DisplayName("防线3：batchSave 掩码出现在 example/description 也要拦（不只 fieldName）")
    void batchSave_maskInOtherColumns_rejected() {
        ApiParamBatchSaveRequest req = new ApiParamBatchSaveRequest();
        req.setApiId(1L);
        List<ApiParamDto> items = new ArrayList<>();
        ApiParamDto d = new ApiParamDto();
        d.setFieldName("skuId");
        d.setExample(MASK);
        d.setParamType(3);
        items.add(d);
        req.setHeader(items); // 换一个分区，证明防线覆盖全部四个分区

        assertThrows(GatewayException.class, () -> paramService.batchSave(req));
        verify(apiParamMapper, never()).delete(any(QueryWrapper.class));
    }

    @Test
    @DisplayName("防线3：batchSave 全是真值 ⇒ 正常先删后插，且插入的行是密文")
    void batchSave_cleanInput_encryptsOnInsert() {
        ApiParamBatchSaveRequest req = new ApiParamBatchSaveRequest();
        req.setApiId(1L);
        List<ApiParamDto> items = new ArrayList<>();
        ApiParamDto d = new ApiParamDto();
        d.setFieldName("skuId");
        d.setExample("1001");
        d.setDescription("商品ID");
        d.setFieldType("string");
        d.setParamType(3);
        items.add(d);
        req.setRequest(items);

        paramService.batchSave(req);

        verify(apiParamMapper).delete(any(QueryWrapper.class));
        ArgumentCaptor<ApiParam> cap = ArgumentCaptor.forClass(ApiParam.class);
        verify(apiParamMapper).insert(cap.capture());
        ApiParam saved = cap.getValue();
        assertTrue(crypto.isEncrypted(saved.getFieldName()));
        assertEquals("skuId", crypto.decryptField(saved.getFieldName()));
        assertEquals("string", saved.getFieldType(), "结构列不加密");
    }

    // =====================================================================
    // 读路径：按可见性解密 / 掩码
    // =====================================================================

    @Test
    @DisplayName("list 白名单外 ⇒ 内容三列掩码、结构列保真、masked=true")
    void list_outsideWhitelist_masksContentKeepsStructure() {
        when(interfaceVisibilityService.resolveViewer()).thenReturn(maskedViewer());
        when(apiInterfaceMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(iface(1L, 7L)));

        ApiParam row = new ApiParam();
        row.setId(1L);
        row.setApiId(1L);
        row.setParamType(3);
        row.setFieldName(crypto.encryptField("skuId"));
        row.setExample(crypto.encryptField("1001"));
        row.setDescription(crypto.encryptField("商品ID"));
        row.setFieldType("string");
        row.setErrorCode("E001");
        when(apiParamMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row));

        List<ApiParamDto> out = paramService.list(1L, 3, null);

        assertEquals(1, out.size());
        ApiParamDto d = out.get(0);
        assertEquals(MASK, d.getFieldName());
        assertEquals(MASK, d.getExample());
        assertEquals(MASK, d.getDescription());
        assertEquals(Boolean.TRUE, d.getMasked());
        // 结构列必须保真 —— 否则参数页对非白名单用户彻底空白
        assertEquals("string", d.getFieldType());
        assertEquals("E001", d.getErrorCode());
        assertEquals(Integer.valueOf(3), d.getParamType());
    }

    @Test
    @DisplayName("list 白名单内 ⇒ 三列解密为明文、masked=false")
    void list_insideWhitelist_returnsPlaintext() {
        InterfaceViewer superAdmin = new InterfaceViewer();
        superAdmin.setProtectionEnabled(true);
        superAdmin.setSuperAdmin(true);
        superAdmin.setWhitelistEmpty(true);
        when(interfaceVisibilityService.resolveViewer()).thenReturn(superAdmin);
        when(apiInterfaceMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(iface(1L, 7L)));

        ApiParam row = new ApiParam();
        row.setId(1L);
        row.setApiId(1L);
        row.setParamType(3);
        row.setFieldName(crypto.encryptField("skuId"));
        row.setExample(crypto.encryptField("1001"));
        row.setDescription(crypto.encryptField("商品ID"));
        when(apiParamMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row));

        List<ApiParamDto> out = paramService.list(1L, 3, null);

        assertEquals("skuId", out.get(0).getFieldName());
        assertEquals("1001", out.get(0).getExample());
        assertEquals("商品ID", out.get(0).getDescription());
        assertEquals(Boolean.FALSE, out.get(0).getMasked());
    }

    @Test
    @DisplayName("list owner 本人可见（即便白名单为空且非超管）")
    void list_ownerSeesOwnInterface() {
        InterfaceViewer owner = new InterfaceViewer();
        owner.setProtectionEnabled(true);
        owner.setUid(7L);
        owner.setWhitelistEmpty(true);
        when(interfaceVisibilityService.resolveViewer()).thenReturn(owner);
        when(apiInterfaceMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(iface(1L, 7L)));

        ApiParam row = new ApiParam();
        row.setId(1L);
        row.setApiId(1L);
        row.setParamType(3);
        row.setFieldName(crypto.encryptField("skuId"));
        when(apiParamMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row));

        List<ApiParamDto> out = paramService.list(1L, 3, null);
        assertEquals("skuId", out.get(0).getFieldName());

        // 换成一个"非 owner 的普通用户" ⇒ 立刻掩码
        InterfaceViewer stranger = new InterfaceViewer();
        stranger.setProtectionEnabled(true);
        stranger.setUid(8L);
        stranger.setWhitelistEmpty(true);
        when(interfaceVisibilityService.resolveViewer()).thenReturn(stranger);
        List<ApiParamDto> masked = paramService.list(1L, 3, null);
        assertEquals(MASK, masked.get(0).getFieldName());
    }

    @Test
    @DisplayName("list 开关关闭 ⇒ 直接明文、不掩码（含历史明文行）")
    void list_switchOff_plaintext() {
        when(configService.isEnabled()).thenReturn(false);
        when(interfaceVisibilityService.resolveViewer()).thenReturn(InterfaceViewer.unprotected());
        when(apiInterfaceMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(iface(1L, 7L)));

        ApiParam row = new ApiParam();
        row.setId(1L);
        row.setApiId(1L);
        row.setParamType(3);
        row.setFieldName("legacyPlainField"); // 历史明文行
        when(apiParamMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(Collections.singletonList(row));

        List<ApiParamDto> out = paramService.list(1L, 3, null);
        assertEquals("legacyPlainField", out.get(0).getFieldName());
        assertEquals(Boolean.FALSE, out.get(0).getMasked());
    }

    // =====================================================================
    // 防线 4：接口路径回写防线
    // =====================================================================

    @Test
    @DisplayName("🔴 防线4：updateInterface 提交掩码路径 ⇒ 用库中原密文回填（no-op）")
    void updateInterface_maskPath_keepsStoredCiphertext() {
        String storedPath = crypto.encryptField("/order/create");
        ApiInterface stored = iface(1L, 7L);
        stored.setInterfacePath(storedPath);
        stored.setInterfacePathHash(crypto.blindIndex("/order/create"));
        when(apiInterfaceMapper.selectById(1L)).thenReturn(stored);
        when(apiGroupMapper.selectById(10L)).thenReturn(new ApiGroup());

        ApiInterface incoming = new ApiInterface();
        incoming.setGroupId(10L);
        incoming.setInterfaceName("下单接口");
        incoming.setInterfacePath(MASK);          // 看不见 ⇒ 前端回传掩码
        incoming.setInterfacePathHash(null);

        interfaceService.updateInterface(1L, incoming);

        ArgumentCaptor<ApiInterface> cap = ArgumentCaptor.forClass(ApiInterface.class);
        verify(apiInterfaceMapper).updateById(cap.capture());
        ApiInterface saved = cap.getValue();

        assertEquals(storedPath, saved.getInterfacePath(), "路径被掩码写坏了 ⇒ 网关会永久 404");
        assertEquals(crypto.blindIndex("/order/create"), saved.getInterfacePathHash(),
                "盲索引必须一起保住，否则网关等值查询也断");
        assertEquals("/order/create", crypto.decryptField(saved.getInterfacePath()));
    }

    @Test
    @DisplayName("防线4：updateInterface 提交空路径 ⇒ 同样按『本次不改』处理")
    void updateInterface_blankPath_keepsStored() {
        String storedPath = crypto.encryptField("/order/create");
        ApiInterface stored = iface(1L, 7L);
        stored.setInterfacePath(storedPath);
        stored.setInterfacePathHash(crypto.blindIndex("/order/create"));
        when(apiInterfaceMapper.selectById(1L)).thenReturn(stored);
        when(apiGroupMapper.selectById(10L)).thenReturn(new ApiGroup());

        ApiInterface incoming = new ApiInterface();
        incoming.setGroupId(10L);
        incoming.setInterfacePath("");
        incoming.setInterfacePathHash(null);

        interfaceService.updateInterface(1L, incoming);

        ArgumentCaptor<ApiInterface> cap = ArgumentCaptor.forClass(ApiInterface.class);
        verify(apiInterfaceMapper).updateById(cap.capture());
        assertEquals(storedPath, cap.getValue().getInterfacePath());
    }

    @Test
    @DisplayName("防线4：updateInterface 提交新明文路径 ⇒ 加密 + 重算盲索引")
    void updateInterface_newPlainPath_encryptedAndHashed() {
        String storedPath = crypto.encryptField("/order/create");
        ApiInterface stored = iface(1L, 7L);
        stored.setInterfacePath(storedPath);
        stored.setInterfacePathHash(crypto.blindIndex("/order/create"));
        when(apiInterfaceMapper.selectById(1L)).thenReturn(stored);
        when(apiGroupMapper.selectById(10L)).thenReturn(new ApiGroup());

        ApiInterface incoming = new ApiInterface();
        incoming.setGroupId(10L);
        incoming.setInterfacePath("/order/createV2");

        interfaceService.updateInterface(1L, incoming);

        ArgumentCaptor<ApiInterface> cap = ArgumentCaptor.forClass(ApiInterface.class);
        verify(apiInterfaceMapper).updateById(cap.capture());
        ApiInterface saved = cap.getValue();

        assertTrue(crypto.isEncrypted(saved.getInterfacePath()));
        assertEquals("/order/createV2", crypto.decryptField(saved.getInterfacePath()));
        assertEquals(crypto.blindIndex("/order/createV2"), saved.getInterfacePathHash(),
                "路径变了，盲索引必须跟着重算");
    }

    @Test
    @DisplayName("createInterface：落库前加密、返回值还原明文（响应体不露 enc:v1: 串）")
    void createInterface_encryptsOnPersist_returnsPlaintext() {
        when(apiGroupMapper.selectById(10L)).thenReturn(new ApiGroup());

        ApiInterface incoming = new ApiInterface();
        incoming.setGroupId(10L);
        incoming.setInterfaceName("下单接口");
        incoming.setInterfacePath("/order/create");

        final String[] persisted = new String[1];
        when(apiInterfaceMapper.insert(any(ApiInterface.class))).thenAnswer(inv -> {
            persisted[0] = inv.getArgument(0, ApiInterface.class).getInterfacePath();
            return 1;
        });

        ApiInterface returned = interfaceService.createInterface(incoming);

        assertTrue(crypto.isEncrypted(persisted[0]), "落库值必须是密文");
        assertEquals("/order/create", crypto.decryptField(persisted[0]));
        assertEquals("/order/create", returned.getInterfacePath(), "返回值必须还原成明文");
        assertFalse(returned.getInterfacePath().startsWith(InterfaceCryptoService.ENC_PREFIX));
    }

    // =====================================================================
    // 列表出参的路径可见性
    // =====================================================================

    @Test
    @DisplayName("pageQueryEnriched 白名单外 ⇒ 路径掩码且 pathMasked=true（实体不被就地改写）")
    void pageQueryEnriched_masksPathOutsideWhitelist() {
        when(interfaceVisibilityService.resolveViewer()).thenReturn(maskedViewer());

        ApiInterface row = iface(1L, 7L);
        row.setInterfacePath(crypto.encryptField("/order/create"));
        row.setGroupId(null); // 免去分组名查询

        when(apiInterfaceMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<ApiInterface> p = inv.getArgument(0);
                    p.setRecords(Collections.singletonList(row));
                    p.setTotal(1);
                    return p;
                });

        PageResult<InterfaceListVo> res = interfaceService.pageQueryEnriched(1, 10, null, null);

        assertEquals(1, res.getRecords().size());
        InterfaceListVo vo = (InterfaceListVo) res.getRecords().get(0);
        assertEquals(MASK, vo.getInterfacePath());
        assertEquals(Boolean.TRUE, vo.getPathMasked());
        // 🔴 实体本身未被就地改写（否则同一次请求的其他分支会拿到掩码）
        assertTrue(crypto.isEncrypted(row.getInterfacePath()));
    }

    @Test
    @DisplayName("pageQueryEnriched 白名单内 ⇒ 路径明文且 pathMasked=false")
    void pageQueryEnriched_plaintextInsideWhitelist() {
        InterfaceViewer superAdmin = new InterfaceViewer();
        superAdmin.setProtectionEnabled(true);
        superAdmin.setSuperAdmin(true);
        when(interfaceVisibilityService.resolveViewer()).thenReturn(superAdmin);

        ApiInterface row = iface(1L, 7L);
        row.setInterfacePath(crypto.encryptField("/order/create"));
        row.setGroupId(null);

        when(apiInterfaceMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<ApiInterface> p = inv.getArgument(0);
                    p.setRecords(Collections.singletonList(row));
                    p.setTotal(1);
                    return p;
                });

        PageResult<InterfaceListVo> res = interfaceService.pageQueryEnriched(1, 10, null, null);

        InterfaceListVo vo = (InterfaceListVo) res.getRecords().get(0);
        assertEquals("/order/create", vo.getInterfacePath());
        assertEquals(Boolean.FALSE, vo.getPathMasked());
    }

    @Test
    @DisplayName("pageQueryEnriched 空页 ⇒ 不报错（list 为空时 loadGroupNames 走空分支）")
    void pageQueryEnriched_emptyPage() {
        when(interfaceVisibilityService.resolveViewer()).thenReturn(maskedViewer());
        when(apiInterfaceMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PageResult<InterfaceListVo> res = interfaceService.pageQueryEnriched(1, 10, "不存在", null);

        assertTrue(res.getRecords().isEmpty());
        verify(apiGroupMapper, never()).selectBatchIds(any());
        verify(interfaceVisibilityService, times(1)).resolveViewer();
    }

    // ---------------------------------------------------------------------

    private static ApiInterface iface(Long id, Long ownerId) {
        ApiInterface i = new ApiInterface();
        i.setId(id);
        i.setOwnerId(ownerId);
        i.setRequestMethod("POST");
        i.setStatus(1);
        return i;
    }
}
