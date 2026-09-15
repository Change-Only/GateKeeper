package com.gatekeeper.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.dto.ApiParamBatchSaveRequest;
import com.gatekeeper.dto.ApiParamBatchSaveResult;
import com.gatekeeper.dto.ApiParamCheckResult;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.dto.ApiParamImportResult;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.ApiParamMapper;
import com.gatekeeper.security.InterfaceViewer;
import com.gatekeeper.service.ApiParamService;
import com.gatekeeper.service.InterfaceVisibilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 接口参数服务实现 — T03b 接口生命周期子资源之一
 *
 * <p>校验点：
 * <ul>
 *   <li>create 必填 apiId / fieldName / paramType</li>
 *   <li>update 仅允许修改可编辑字段，结构键（apiId / parentId / paramType）不可改</li>
 *   <li>tree 基于 parent_id 构建嵌套树，根节点 parentId = 0</li>
 *   <li>batchSave 按分区「先删后插」原子全量替换</li>
 *   <li>importParams 先全量校验、全通过才落库</li>
 *   <li>checkRequired 校验必填参数类型完整 + 错误码完整 + 至少一条入参</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiParamServiceImpl extends ServiceImpl<ApiParamMapper, ApiParam> implements ApiParamService {

    /** paramType：Header */
    private static final int PT_HEADER = 1;
    /** paramType：Request 入参（BODY） */
    private static final int PT_REQUEST = 3;
    /** paramType：Response 出参 */
    private static final int PT_RESPONSE = 4;
    /** paramType：Error 错误码 */
    private static final int PT_ERROR = 5;

    /** T17：参数契约内容的字段级加解密 */
    private final com.gatekeeper.crypto.InterfaceCryptoService interfaceCryptoService;
    /** T17：可见性判定（参数行的可见性取决于其所属接口的 owner） */
    private final InterfaceVisibilityService interfaceVisibilityService;
    /** T17：查参数所属接口的 owner_id 用 */
    private final ApiInterfaceMapper apiInterfaceMapper;

    @Override
    public List<ApiParamDto> list(Long apiId, Integer paramType, Long parentId) {
        QueryWrapper<ApiParam> wrapper = new QueryWrapper<>();
        if (apiId != null) {
            wrapper.eq("api_id", apiId);
        }
        if (paramType != null) {
            wrapper.eq("param_type", paramType);
        }
        if (parentId != null) {
            wrapper.eq("parent_id", parentId);
        }
        wrapper.orderByAsc("sort_order").orderByAsc("id");
        List<ApiParam> rows = baseMapper.selectList(wrapper);
        InterfaceViewer viewer = interfaceVisibilityService.resolveViewer();
        Map<Long, Long> owners = loadOwnerIds(rows);
        return rows.stream().map(p -> toVisibleDto(p, viewer, owners)).collect(Collectors.toList());
    }

    @Override
    public ApiParamDto get(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("参数ID不能为空");
        }
        ApiParam entity = baseMapper.selectById(id);
        if (entity == null) {
            throw GatewayException.notFound("接口参数不存在: id=" + id);
        }
        InterfaceViewer viewer = interfaceVisibilityService.resolveViewer();
        return toVisibleDto(entity, viewer, loadOwnerIds(java.util.Collections.singletonList(entity)));
    }

    @Override
    public List<ApiParamDto> tree(Long apiId) {
        if (apiId == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        // 一次性加载该接口下所有参数，内存构建树
        List<ApiParam> all = baseMapper.selectList(
                new QueryWrapper<ApiParam>().eq("api_id", apiId).orderByAsc("sort_order").orderByAsc("id"));

        InterfaceViewer viewer = interfaceVisibilityService.resolveViewer();
        Map<Long, Long> owners = loadOwnerIds(all);

        Map<Long, ApiParamDto> dtoMap = new LinkedHashMap<>();
        for (ApiParam p : all) {
            dtoMap.put(p.getId(), toVisibleDto(p, viewer, owners));
        }

        List<ApiParamDto> roots = new ArrayList<>();
        for (ApiParam p : all) {
            ApiParamDto dto = dtoMap.get(p.getId());
            long pid = (p.getParentId() == null) ? 0L : p.getParentId();
            if (pid == 0L) {
                roots.add(dto);
            } else {
                ApiParamDto parent = dtoMap.get(pid);
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        parent.setChildren(new ArrayList<>());
                    }
                    parent.getChildren().add(dto);
                } else {
                    // 父节点缺失（脏数据），兜底挂到根
                    roots.add(dto);
                }
            }
        }
        return roots;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiParamDto create(ApiParamDto dto) {
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (dto.getApiId() == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        if (!StringUtils.hasText(dto.getFieldName())) {
            throw GatewayException.badRequest("字段名不能为空");
        }
        if (interfaceCryptoService.isMask(dto.getFieldName())) {
            // 掩码是"你看不到"，不是可提交的内容；照收会把 **** 写进库
            throw GatewayException.badRequest(
                    "字段名不能为掩码 —— 当前账号无权查看该接口参数明文，请勿把掩码回传");
        }
        if (dto.getParamType() == null) {
            throw GatewayException.badRequest("参数类型不能为空");
        }
        ApiParam entity = new ApiParam();
        BeanUtils.copyProperties(dto, entity);
        entity.setId(null); // 创建场景忽略 id
        if (entity.getParentId() == null) {
            entity.setParentId(0L);
        }
        if (entity.getRequired() == null) {
            entity.setRequired(0);
        }
        if (entity.getSensitive() == null) {
            entity.setSensitive(0);
        }
        if (entity.getSortOrder() == null) {
            entity.setSortOrder(0);
        }
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        // T17 写路径：契约内容三列加密落库
        interfaceCryptoService.applyToParam(entity);
        baseMapper.insert(entity);
        log.info("ApiParam created: id={}, apiId={}, fieldName={}", entity.getId(), entity.getApiId(),
                // 日志里绝不写明文契约内容，只记摘要痕迹
                interfaceCryptoService.isEnabled() ? "[已加密]" : entity.getFieldName());
        return toVisibleDto(entity, interfaceVisibilityService.resolveViewer(),
                loadOwnerIds(java.util.Collections.singletonList(entity)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ApiParamDto dto) {
        if (id == null) {
            throw GatewayException.badRequest("参数ID不能为空");
        }
        if (dto == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        ApiParam existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("接口参数不存在: id=" + id);
        }
        // 仅允许修改的字段（结构键 apiId / parentId / paramType 不可改）
        //
        // 🔴 T17 掩码回写防线：库中的 fieldName/example/description 是密文，
        // 不在白名单内的用户拿到的是掩码，他"什么都不改直接保存"时提交的就是 ****。
        // 因此掩码一律视为"本次不改这一列"（保持库中原值），绝不能把 **** 写进库。
        if (StringUtils.hasText(dto.getFieldName()) && !interfaceCryptoService.isMask(dto.getFieldName())) {
            existing.setFieldName(dto.getFieldName());
        }
        if (StringUtils.hasText(dto.getFieldType())) {
            existing.setFieldType(dto.getFieldType());
        }
        if (dto.getRequired() != null) {
            existing.setRequired(dto.getRequired());
        }
        if (StringUtils.hasText(dto.getExample()) && !interfaceCryptoService.isMask(dto.getExample())) {
            existing.setExample(dto.getExample());
        }
        if (StringUtils.hasText(dto.getErrorCode())) {
            existing.setErrorCode(dto.getErrorCode());
        }
        if (dto.getHttpStatus() != null) {
            existing.setHttpStatus(dto.getHttpStatus());
        }
        if (dto.getSensitive() != null) {
            existing.setSensitive(dto.getSensitive());
        }
        if (StringUtils.hasText(dto.getEncryptRule())) {
            existing.setEncryptRule(dto.getEncryptRule());
        }
        if (dto.getSortOrder() != null) {
            existing.setSortOrder(dto.getSortOrder());
        }
        if (StringUtils.hasText(dto.getDescription()) && !interfaceCryptoService.isMask(dto.getDescription())) {
            existing.setDescription(dto.getDescription());
        }
        existing.setUpdatedAt(LocalDateTime.now());
        // T17 写路径：把（可能仍是密文的）三列统一过一遍 ——
        // 未改动的列已是密文，encryptField 幂等跳过；改动过的是明文，这里加密。
        interfaceCryptoService.applyToParam(existing);
        baseMapper.updateById(existing);
        log.info("ApiParam updated: id={}", id);
    }

    // =====================================================================
    // T03b 批量契约维护
    // =====================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiParamBatchSaveResult batchSave(ApiParamBatchSaveRequest req) {
        if (req == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        Long apiId = req.getApiId();
        if (apiId == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        ApiParamBatchSaveResult result = new ApiParamBatchSaveResult();
        int total = 0;
        if (req.getHeader() != null) {
            result.setHeader(replaceSection(apiId, PT_HEADER, req.getHeader()));
            total += result.getHeader();
        }
        if (req.getRequest() != null) {
            result.setRequest(replaceSection(apiId, PT_REQUEST, req.getRequest()));
            total += result.getRequest();
        }
        if (req.getResponse() != null) {
            result.setResponse(replaceSection(apiId, PT_RESPONSE, req.getResponse()));
            total += result.getResponse();
        }
        if (req.getError() != null) {
            result.setError(replaceSection(apiId, PT_ERROR, req.getError()));
            total += result.getError();
        }
        result.setTotal(total);
        log.info("ApiParam batchSave: apiId={}, total={}", apiId, total);
        return result;
    }

    /**
     * 全量替换某接口某 paramType 的参数（先删后插）。
     *
     * @param apiId     接口ID
     * @param paramType 参数类型
     * @param items     新参数列表
     * @return 实际写入条数
     */
    private int replaceSection(Long apiId, int paramType, List<ApiParamDto> items) {
        // 🔴 T17 关键防线：本方法语义是「**先删后插**的全量替换」。
        // 若提交内容里带掩码（= 调用者不在可见性白名单内、压根看不到真值），
        // 这些 **** 会**覆盖掉不可见字段的真值**，且原值已随"先删"消失 —— 不可逆的数据损毁。
        // 因此这里直接 400，而不是"尽力而为"：让用户先拿到明文权限，或改用单条编辑。
        assertNoMaskInBatch(items);

        // 1) 先删：该接口该分区全量清除
        baseMapper.delete(new QueryWrapper<ApiParam>()
                .eq("api_id", apiId)
                .eq("param_type", paramType));

        // 2) 后插：按提交顺序重排 sort_order
        int sort = 0;
        int inserted = 0;
        for (ApiParamDto item : items) {
            if (item == null) {
                continue;
            }
            if (!StringUtils.hasText(item.getFieldName())) {
                throw GatewayException.badRequest("参数 fieldName 不能为空（paramType=" + paramType + "）");
            }
            ApiParam entity = new ApiParam();
            BeanUtils.copyProperties(item, entity);
            entity.setId(null); // 全量替换：忽略入参 id，重新生成
            entity.setApiId(apiId);
            entity.setParamType(paramType);
            if (entity.getParentId() == null) {
                entity.setParentId(0L);
            }
            if (entity.getRequired() == null) {
                entity.setRequired(0);
            }
            if (entity.getSensitive() == null) {
                entity.setSensitive(0);
            }
            if (!StringUtils.hasText(entity.getFieldType())) {
                entity.setFieldType("string");
            }
            if (entity.getSortOrder() == null) {
                entity.setSortOrder(sort);
            }
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            // T17 写路径：契约内容三列加密落库
            interfaceCryptoService.applyToParam(entity);
            baseMapper.insert(entity);
            sort++;
            inserted++;
        }
        return inserted;
    }

    /**
     * 全量替换的掩码防线（T17）。
     *
     * <p>掩码只在「保护启用 且 调用者不在白名单」时出现，所以这里无需再查一遍可见性 ——
     * 提交体里出现掩码本身就已经说明"提交者看不到真值"，此时任何全量替换都不该被放行。</p>
     */
    private void assertNoMaskInBatch(List<ApiParamDto> items) {
        if (items == null) {
            return;
        }
        for (ApiParamDto it : items) {
            if (it == null) {
                continue;
            }
            if (interfaceCryptoService.isMask(it.getFieldName())
                    || interfaceCryptoService.isMask(it.getExample())
                    || interfaceCryptoService.isMask(it.getDescription())) {
                throw GatewayException.badRequest(
                        "提交内容包含不可见字段的掩码，无法执行全量替换（会覆盖你看不到的真值）。"
                                + "请联系管理员把你加入「接口信息可见性白名单」，或改用单条编辑。");
            }
        }
    }

    @Override
    public String importTemplate() {
        Map<String, Object> tpl = new LinkedHashMap<>();
        tpl.put("apiId", null);
        tpl.put("header", sampleHeader());
        tpl.put("request", sampleRequest());
        tpl.put("response", sampleResponse());
        tpl.put("error", sampleError());
        return JSONUtil.toJsonPrettyStr(tpl);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApiParamImportResult importParams(ApiParamBatchSaveRequest req) {
        if (req == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (req.getApiId() == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        // 1) 全量校验（不落库）
        List<ApiParamImportResult.ImportError> errors = new ArrayList<>();
        collectErrors(req.getHeader(), "header", errors);
        collectErrors(req.getRequest(), "request", errors);
        collectErrors(req.getResponse(), "response", errors);
        collectErrors(req.getError(), "error", errors);

        ApiParamImportResult res = new ApiParamImportResult();
        if (!errors.isEmpty()) {
            res.setSuccess(false);
            res.setImported(0);
            res.setErrors(errors);
            log.info("ApiParam import rejected: apiId={}, errorCount={}", req.getApiId(), errors.size());
            return res;
        }
        // 2) 全通过 → 全量替换落库
        ApiParamBatchSaveResult saved = batchSave(req);
        res.setSuccess(true);
        res.setImported(saved.getTotal());
        log.info("ApiParam import success: apiId={}, imported={}", req.getApiId(), saved.getTotal());
        return res;
    }

    @Override
    public ApiParamCheckResult checkRequired(Long apiId) {
        if (apiId == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        List<ApiParam> all = baseMapper.selectList(
                new QueryWrapper<ApiParam>().eq("api_id", apiId).orderByAsc("sort_order").orderByAsc("id"));

        ApiParamCheckResult res = new ApiParamCheckResult();
        res.setTotal(all.size());
        // T17：就绪度校验会往 issues 里带 fieldName，出参前同样要按可见性还原/掩码
        InterfaceViewer viewer = interfaceVisibilityService.resolveViewer();
        Map<Long, Long> owners = loadOwnerIds(all);
        boolean maskContent = viewer.mustMask(owners.get(apiId));
        int requiredCount = 0;
        int requestCount = 0;
        for (ApiParam p : all) {
            int pt = p.getParamType() == null ? 0 : p.getParamType();
            if (pt == PT_REQUEST) {
                requestCount++;
            }
            boolean required = p.getRequired() != null && p.getRequired() == 1;
            if (required) {
                requiredCount++;
            }
            String section = sectionOf(pt);
            String fieldName = maskContent
                    ? interfaceCryptoService.maskPath()
                    : interfaceCryptoService.decryptField(p.getFieldName());
            if (required && !StringUtils.hasText(p.getFieldType())) {
                res.getIssues().add(new ApiParamCheckResult.Issue(
                        p.getId(), section, fieldName, "必填参数缺少字段类型(fieldType)"));
            }
            if (pt == PT_ERROR && !StringUtils.hasText(p.getErrorCode())) {
                res.getIssues().add(new ApiParamCheckResult.Issue(
                        p.getId(), section, fieldName, "错误码分区缺少 errorCode"));
            }
        }
        res.setRequiredCount(requiredCount);
        if (requestCount == 0) {
            res.getIssues().add(new ApiParamCheckResult.Issue(
                    null, "request", null, "未定义任何 Request 入参（paramType=3）"));
        }
        res.setPassed(res.getIssues().isEmpty());
        return res;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /**
     * 收集某分区的导入校验错误。
     */
    private void collectErrors(List<ApiParamDto> items, String section,
                               List<ApiParamImportResult.ImportError> errors) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            ApiParamDto it = items.get(i);
            if (it == null) {
                errors.add(new ApiParamImportResult.ImportError(section, i, null, "条目为空"));
                continue;
            }
            if (!StringUtils.hasText(it.getFieldName())) {
                errors.add(new ApiParamImportResult.ImportError(section, i, it.getFieldName(), "fieldName 不能为空"));
            }
            if ("error".equals(section) && !StringUtils.hasText(it.getErrorCode())) {
                errors.add(new ApiParamImportResult.ImportError(section, i, it.getFieldName(), "错误码分区必须提供 errorCode"));
            }
        }
    }

    /**
     * paramType → 分区名。
     */
    private String sectionOf(int paramType) {
        switch (paramType) {
            case PT_HEADER:
                return "header";
            case 2:
                return "query";
            case PT_REQUEST:
                return "request";
            case PT_RESPONSE:
                return "response";
            case PT_ERROR:
                return "error";
            default:
                return "other";
        }
    }

    private List<Map<String, Object>> sampleHeader() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(field("Content-Type", "string", 1, "application/json", null, null, "内容类型"));
        return list;
    }

    private List<Map<String, Object>> sampleRequest() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(field("outOrderNo", "string", 1, "OUT202401010001", null, null, "外部订单号"));
        list.add(field("userId", "int", 1, "10086", null, null, "用户ID"));
        list.add(field("amount", "number", 1, "99.99", null, null, "金额"));
        list.add(field("items", "array", 0, "[]", null, null, "商品明细数组"));
        list.add(field("remark", "string", 0, "加急", null, null, "备注"));
        return list;
    }

    private List<Map<String, Object>> sampleResponse() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(field("orderNo", "string", 1, "2024010100001", null, null, "平台订单号"));
        list.add(field("status", "string", 1, "PAID", null, null, "订单状态"));
        list.add(field("phone", "string", 0, "13800001111", 1, "MASK", "手机号（脱敏）"));
        return list;
    }

    private List<Map<String, Object>> sampleError() {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> e1 = field("ORDER_DUPLICATE", "string", 0, "ORDER_DUPLICATE", null, null, "订单重复");
        e1.put("errorCode", "ORDER_DUPLICATE");
        e1.put("httpStatus", 409);
        list.add(e1);
        Map<String, Object> e2 = field("PARAM_INVALID", "string", 0, "PARAM_INVALID", null, null, "参数非法");
        e2.put("errorCode", "PARAM_INVALID");
        e2.put("httpStatus", 400);
        list.add(e2);
        return list;
    }

    /**
     * 构造一条模板字段（LinkedHashMap 保证输出字段顺序稳定）。
     */
    private Map<String, Object> field(String name, String type, int required, String example,
                                      Integer sensitive, String encryptRule, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fieldName", name);
        m.put("fieldType", type);
        m.put("required", required);
        m.put("example", example);
        if (sensitive != null) {
            m.put("sensitive", sensitive);
        }
        if (encryptRule != null) {
            m.put("encryptRule", encryptRule);
        }
        m.put("description", desc);
        return m;
    }

    /**
     * Entity → DTO（<b>无可见性处理</b>）。
     *
     * <p>⚠ 仅供「不需要出参给控制台」的内部场景使用。任何会进响应体的路径请用
     * {@link #toVisibleDto(ApiParam, InterfaceViewer, Map)}，否则会把密文或未掩码的
     * 明文泄露出去。</p>
     */
    private ApiParamDto toDto(ApiParam entity) {
        if (entity == null) {
            return null;
        }
        ApiParamDto dto = new ApiParamDto();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    /**
     * Entity → DTO（T17：按可见性解密或掩码）。
     *
     * <p>掩码只作用在「契约内容」三列（fieldName / example / description）；
     * 结构列（paramType / fieldType / required / errorCode / httpStatus /
     * sensitive / encryptRule / sortOrder）保持真值 —— 与
     * {@code InterfaceServiceImpl.paramDtos} 和接口文档导出口径一致，三处必须同改。</p>
     *
     * @param entity 库中参数（其内容列为密文或历史明文）
     * @param viewer 本次请求的可见性上下文
     * @param owners {@code apiId → owner_id} 映射（批量预取，避免逐行查库）
     */
    private ApiParamDto toVisibleDto(ApiParam entity, InterfaceViewer viewer, Map<Long, Long> owners) {
        ApiParamDto dto = toDto(entity);
        if (dto == null) {
            return null;
        }
        if (viewer != null && viewer.mustMask(owners == null ? null : owners.get(entity.getApiId()))) {
            dto.setFieldName(interfaceCryptoService.maskPath());
            dto.setExample(interfaceCryptoService.maskPath());
            dto.setDescription(interfaceCryptoService.maskPath());
            dto.setMasked(Boolean.TRUE);
        } else {
            dto.setFieldName(interfaceCryptoService.decryptField(entity.getFieldName()));
            dto.setExample(interfaceCryptoService.decryptField(entity.getExample()));
            dto.setDescription(interfaceCryptoService.decryptField(entity.getDescription()));
            dto.setMasked(Boolean.FALSE);
        }
        return dto;
    }

    /**
     * 批量预取 {@code apiId → owner_id}（T17）。
     *
     * <p>可见性判定依赖"参数所属接口的负责人"，逐行查库会变成 N+1；
     * 这里把本页出现的 apiId 去重后一次查完。</p>
     */
    private Map<Long, Long> loadOwnerIds(List<ApiParam> rows) {
        Map<Long, Long> out = new java.util.HashMap<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        java.util.Set<Long> ids = rows.stream()
                .map(ApiParam::getApiId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return out;
        }
        List<ApiInterface> ifaces = apiInterfaceMapper.selectList(
                new QueryWrapper<ApiInterface>().select("id", "owner_id").in("id", ids));
        if (ifaces != null) {
            for (ApiInterface i : ifaces) {
                out.put(i.getId(), i.getOwnerId());
            }
        }
        return out;
    }
}
