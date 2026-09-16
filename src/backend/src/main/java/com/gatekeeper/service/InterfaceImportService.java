package com.gatekeeper.service;

import com.gatekeeper.dto.InterfaceImportRequest;
import com.gatekeeper.dto.InterfaceImportResult;

/**
 * OpenAPI 3.x 文档导入服务（T18）。
 *
 * <p>职责边界：<b>把一份 OpenAPI 文档变成一批「接口资产 + 参数契约」</b>。
 * 与单条新建接口的区别只在「批量」与「部分成功」两点：</p>
 * <ul>
 *   <li><b>整批失败</b>（服务层直接抛 400，一条都不写）：
 *       未选分组 / 分组不存在 / 内容为空 / 文档不是 OpenAPI 3.x / 文档结构解析不出来 / paths 为空。</li>
 *   <li><b>单条失败</b>（计入 {@code failed}，其余继续）：
 *       该 operation 落库时出错（如接口编码冲突、字段超长）。
 *       单条失败是<b>独立事务</b>，不会把整批回滚掉。</li>
 * </ul>
 *
 * <p>不做的事（刻意）：不写 {@code api_change_log}（多接口一次操作无法归属单条 apiId）、
 * 不创建 {@code api_version}、不猜 {@code backend_url} —— 理由见
 * {@code docs/T18-契约记录.md}。</p>
 *
 * @author GateKeeper
 * @since T18 (OpenAPI 导入)
 */
public interface InterfaceImportService {

    /**
     * 导入一份 OpenAPI 文档。
     *
     * @param req          导入请求（groupId 必填 + 文档正文）
     * @param operatorId   操作人用户 id（取自请求属性 {@code X-USER-ID}），写入 {@code api_interface.owner_id}；可为 null
     * @param operatorName 操作人登录名（取自请求属性 {@code X-USERNAME}），写入 {@code api_interface.owner_name}；可为 null
     * @return 导入结果（文档元信息 + 计数 + 逐条明细）
     * @throws com.gatekeeper.exception.GatewayException 400 —— 未选分组 / 分组不存在 / 内容为空 / 非 OpenAPI 3.x
     */
    InterfaceImportResult importSpec(InterfaceImportRequest req, Long operatorId, String operatorName);
}
