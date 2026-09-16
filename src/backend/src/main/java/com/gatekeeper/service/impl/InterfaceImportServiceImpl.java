package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.InterfaceImportRequest;
import com.gatekeeper.dto.InterfaceImportResult;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.openapi.OpenApiParser;
import com.gatekeeper.openapi.ParsedOperation;
import com.gatekeeper.service.InterfaceImportService;
import com.gatekeeper.service.InterfaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * OpenAPI 导入服务实现（T18）。
 *
 * <h3>链路</h3>
 * <pre>
 *   Controller → 本类（编排，无事务）
 *                     ├─ OpenApiParser   解析文档（不碰 DB）
 *                     └─ InterfaceImportWriter  单条写入（独立事务）
 * </pre>
 * <p>三段拆开是为了让失败语义各自独立：解析失败 = 文档问题（整批 400）；
 * 单条落库失败 = 这条接口的问题（计入 failed，余下继续）。</p>
 *
 * <h3>幂等性</h3>
 * <p>用 {@code api_code} 作为去重键：它由「方法 + 路径」确定性推导，且
 * {@code api_interface} 上有唯一键 {@code uk_iface_code(api_code)}。
 * 因此同一份文档导入两次，第二次全部落进 {@code skipped} 而不是产生重复接口 ——
 * 用户「手滑点了两次导入」不会把接口列表翻倍。</p>
 *
 * <h3>为什么用查询判重 + 捕获唯一键异常两道</h3>
 * <p>先查一次是为了给出<b>可读的 skipped 原因</b>（唯一键异常只能拿到数据库原文）；
 * 但查与插之间存在竞态，故唯一键仍在兜底 —— 并发下重复插入会被
 * {@link DuplicateKeyException} 接住并同样归为 skipped，而不是记成 FAILED。</p>
 *
 * @author GateKeeper
 * @since T18 (OpenAPI 导入)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterfaceImportServiceImpl implements InterfaceImportService {

    /** {@code api_interface.api_code} 列宽 */
    private static final int MAX_API_CODE = 64;

    /** 生成 api_code 时的前缀，避免与人手建的编码（如 order.create）语义混淆 */
    private static final String CODE_PREFIX = "imp_";

    /** 超长时截断保留的哈希后缀长度 */
    private static final int HASH_SUFFIX_LEN = 8;

    private final OpenApiParser openApiParser;
    private final InterfaceImportWriter interfaceImportWriter;
    private final InterfaceService interfaceService;
    private final ApiGroupMapper apiGroupMapper;

    @Override
    public InterfaceImportResult importSpec(InterfaceImportRequest req, Long operatorId, String operatorName) {
        requireGroup(req);
        ApiGroup group = apiGroupMapper.selectById(req.getGroupId());
        if (group == null) {
            throw GatewayException.badRequest("所属分组不存在: id=" + req.getGroupId());
        }
        if (!StringUtils.hasText(req.getContent())) {
            throw GatewayException.badRequest("导入内容为空：请选择 OpenAPI 3.0 文档文件，或粘贴其内容");
        }

        // 解析失败（非 3.x / 结构不合法 / paths 为空）一律 400，整批不落库
        OpenApiParser.Spec spec = openApiParser.parse(
                req.getContent().getBytes(StandardCharsets.UTF_8), req.getFileName());

        InterfaceImportResult result = new InterfaceImportResult();
        result.setGroupId(group.getId());
        result.setGroupName(group.getGroupName());
        result.setOpenapiVersion(spec.getOpenapiVersion());
        result.setTitle(spec.getTitle());
        result.setSpecVersion(spec.getVersion());
        result.setServerUrl(spec.getServerUrl());
        result.setTotal(spec.getOperations().size());

        for (ParsedOperation op : spec.getOperations()) {
            importOne(op, group.getId(), operatorId, operatorName, result);
        }

        appendGlobalWarnings(result, spec);
        log.info("OpenAPI import done: groupId={}, total={}, imported={}, skipped={}, failed={}, params={}",
                group.getId(), result.getTotal(), result.getImported(),
                result.getSkipped(), result.getFailed(), result.getParamCount());
        return result;
    }

    /**
     * 硬约束：未选分组不许导入（需求原文「导入时必须先选择分组」）。
     *
     * <p>前端把提交按钮禁用只是体验层闸门，脚本/curl 仍可绕过；
     * 而分组是 T13「环境配置沿分组树继承」的继承链起点，
     * 落一个无分组的接口 = 该接口永远拿不到任何环境配置（同
     * {@code InterfaceServiceImpl.requireGroup} 的理由）。故后端必须独立拦一道。</p>
     */
    private void requireGroup(InterfaceImportRequest req) {
        if (req == null) {
            throw GatewayException.badRequest("请求体不能为空");
        }
        if (req.getGroupId() == null) {
            throw GatewayException.badRequest("请先选择分组：导入前必须指定接口所属分组");
        }
    }

    /**
     * 导入单条 operation —— 任何异常都只影响这一条。
     */
    private void importOne(ParsedOperation op, Long groupId, Long operatorId,
                           String operatorName, InterfaceImportResult result) {
        InterfaceImportResult.Item item = new InterfaceImportResult.Item();
        item.setName(op.getName());
        item.setMethod(op.getMethod());
        item.setPath(op.getPath());
        item.setSource(op.getSourceLocation());
        // 解析期提示（如分区截断）只回给用户看，不共享 list 引用
        item.getWarnings().addAll(op.getWarnings());

        String apiCode = buildApiCode(op);
        try {
            if (apiCodeExists(apiCode)) {
                item.setResult(InterfaceImportResult.Item.SKIPPED);
                item.setMessage("接口编码已存在，视为同一接口已导入：" + apiCode);
                result.setSkipped(result.getSkipped() + 1);
            } else {
                InterfaceImportWriter.WriteOutcome outcome =
                        interfaceImportWriter.writeOne(op, groupId, apiCode, operatorId, operatorName);
                item.setApiId(outcome.getApiId());
                item.setParamCount(outcome.getParamCount());
                item.setResult(InterfaceImportResult.Item.IMPORTED);
                item.setMessage("已导入（接口编码 " + apiCode + "）");
                result.setImported(result.getImported() + 1);
                result.setParamCount(result.getParamCount() + outcome.getParamCount());
            }
        } catch (DuplicateKeyException e) {
            // 查询判重与插入之间的竞态窗口：唯一键 uk_iface_code 兜底
            item.setResult(InterfaceImportResult.Item.SKIPPED);
            item.setMessage("接口编码已存在（并发写入），视为同一接口已导入：" + apiCode);
            result.setSkipped(result.getSkipped() + 1);
            log.warn("OpenAPI import duplicate key skipped: apiCode={}", apiCode);
        } catch (Exception e) {
            item.setResult(InterfaceImportResult.Item.FAILED);
            item.setMessage(rootMessage(e));
            result.setFailed(result.getFailed() + 1);
            log.warn("OpenAPI operation import failed: apiCode={}, reason={}", apiCode, rootMessage(e));
        }
        result.getItems().add(item);
    }

    /**
     * 生成接口编码：{@code imp_<method>_<path 转义>}，超长时尾部换 8 位内容哈希。
     *
     * <p>为什么确定性生成而不是随机：去重键必须是「同一文档 ⇒ 同一编码」，
     * 否则重复导入会不断产生新接口。超长时用<b>内容哈希</b>而非简单截断：
     * 长路径往往前半段相同（{@code /api/v1/order/...}），截断会让不同接口撞成同一编码、
     * 第二个被误判成"已导入"而静默丢掉。</p>
     */
    private String buildApiCode(ParsedOperation op) {
        String raw = CODE_PREFIX + op.getMethod().toLowerCase() + "_" + op.getPath();
        String slug = raw.replaceAll("[^A-Za-z0-9_]+", "_");
        while (slug.contains("__")) {
            slug = slug.replace("__", "_");
        }
        if (slug.length() <= MAX_API_CODE) {
            return slug;
        }
        String hash = shortHash(op.getMethod() + " " + op.getPath());
        int keep = MAX_API_CODE - HASH_SUFFIX_LEN - 1;
        return slug.substring(0, keep) + "_" + hash;
    }

    private boolean apiCodeExists(String apiCode) {
        return interfaceService.count(new QueryWrapper<ApiInterface>().eq("api_code", apiCode)) > 0;
    }

    private String shortHash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < HASH_SUFFIX_LEN / 2; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境异常；退化为 hashCode 也不能抛
            return Integer.toHexString(s.hashCode()).replace('-', '0');
        }
    }

    /**
     * 全局提示：把「导入之后还需要人做什么」说清楚。
     *
     * <p>T18 只建立「接口资产 + 参数契约」。后端地址、版本、发布状态都<b>刻意留空/留草稿</b>，
     * 若不在结果里点明，用户会以为导入残缺。</p>
     */
    private void appendGlobalWarnings(InterfaceImportResult result, OpenApiParser.Spec spec) {
        List<String> w = result.getWarnings();
        w.add("导入的接口一律为「草稿 + 启用」状态、后端服务地址留空："
                + "请在接口详情里补齐后端地址并完成发布，否则网关不会把它对外放行。");
        w.add("导入只建立接口与参数契约，不创建版本记录；如需版本化管理请在「版本管理」中新建。");
        if (StringUtils.hasText(spec.getServerUrl())) {
            w.add("文档声明的 servers[0].url = " + spec.getServerUrl()
                    + "（仅作参考，未写入后端地址 —— 它通常是网关自身的对外地址，直接使用会造成回环）。");
        }
        if (result.getSkipped() > 0) {
            w.add("有 " + result.getSkipped() + " 条因接口编码已存在被跳过：同一份文档重复导入不会产生重复接口。");
        }
    }

    /**
     * 取最内层异常的消息 —— 只有最内层才有"Column 'xxx' too long"这类可照做的信息。
     */
    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        if (!StringUtils.hasText(msg)) {
            msg = t.getClass().getSimpleName();
        }
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
