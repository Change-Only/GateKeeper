package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.dto.ApiParamBatchSaveRequest;
import com.gatekeeper.dto.ApiParamBatchSaveResult;
import com.gatekeeper.dto.ApiParamCheckResult;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.dto.ApiParamImportResult;
import com.gatekeeper.entity.ApiParam;

import java.util.List;

/**
 * 接口参数服务接口 — T03b 接口生命周期子资源之一
 *
 * <p>提供接口参数（api_param）的 CRUD + 树形结构 + 批量契约维护：
 * <ul>
 *   <li>{@link #list} 按 apiId / paramType / parentId 筛选</li>
 *   <li>{@link #tree} 基于 parent_id 构建嵌套树（root = parentId 0）</li>
 *   <li>{@link #get} 详情（不存在抛 404）</li>
 *   <li>{@link #create} 创建（apiId / fieldName / paramType 必填）</li>
 *   <li>{@link #update} 仅修改可编辑字段（不影响 apiId / parentId / paramType 结构键）</li>
 *   <li>{@link #batchSave} 按分区全量替换（先删后插，事务原子）</li>
 *   <li>{@link #importTemplate} 返回导入用 JSON 模板</li>
 *   <li>{@link #importParams} 导入 JSON（先全量校验，全通过才落库）</li>
 *   <li>{@link #checkRequired} 发布前必填参数就绪度校验</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
public interface ApiParamService extends IService<ApiParam> {

    /**
     * 按接口ID / 参数类型 / 父级ID 筛选参数列表。
     *
     * @param apiId     接口ID（可空）
     * @param paramType 参数类型（可空）
     * @param parentId  父级参数ID（可空）
     * @return 参数 DTO 列表
     */
    List<ApiParamDto> list(Long apiId, Integer paramType, Long parentId);

    /**
     * 按主键查询参数详情。
     *
     * @param id 参数 ID
     * @return 参数 DTO（不存在抛 404）
     */
    ApiParamDto get(Long id);

    /**
     * 构建某接口的嵌套参数树（root = parentId 0，children 递归填充）。
     *
     * @param apiId 接口ID
     * @return 根参数 DTO 列表（含 children 嵌套）
     */
    List<ApiParamDto> tree(Long apiId);

    /**
     * 创建接口参数。
     *
     * @param dto 入参（apiId / fieldName / paramType 必填）
     * @return 新建参数 DTO（含主键）
     * @throws com.gatekeeper.exception.GatewayException 必填项缺失抛 400
     */
    ApiParamDto create(ApiParamDto dto);

    /**
     * 更新接口参数（仅 fieldName/fieldType/required/example/errorCode/httpStatus/
     * sensitive/encryptRule/sortOrder/description 可改）。
     *
     * @param id  参数 ID
     * @param dto 入参
     * @throws com.gatekeeper.exception.GatewayException 参数不存在抛 404
     */
    void update(Long id, ApiParamDto dto);

    /**
     * 批量保存：对请求中<strong>非 null 的分区</strong>执行「先删后插」全量替换。
     *
     * <p>同一接口同一 paramType 的数据在单事务内整体替换，保证参数契约原子性。
     * 分区未提交（null）表示「本次不变更该分区」。</p>
     *
     * @param req 批量保存请求（apiId 必填）
     * @return 各分区实际写入条数
     * @throws com.gatekeeper.exception.GatewayException apiId 为空或某条 fieldName 为空抛 400
     */
    ApiParamBatchSaveResult batchSave(ApiParamBatchSaveRequest req);

    /**
     * 生成接口参数导入 JSON 模板（含示例 1 Header + 5 入参 + 3 出参 + 2 错误码）。
     *
     * @return 模板 JSON 字符串
     */
    String importTemplate();

    /**
     * 导入接口参数 JSON。
     *
     * <p>先全量校验：任一条不通过则返回 {@code success=false} 与失败明细，
     * <strong>数据库不发生任何变更</strong>；全部通过才执行与 {@link #batchSave} 一致的全量替换。</p>
     *
     * @param req 导入请求（apiId 必填）
     * @return 导入结果（成功条数 / 失败明细）
     * @throws com.gatekeeper.exception.GatewayException apiId 为空抛 400
     */
    ApiParamImportResult importParams(ApiParamBatchSaveRequest req);

    /**
     * 校验接口的必填参数就绪度（前端发布前检查）。
     *
     * @param apiId 接口ID
     * @return 校验结果（passed + 问题明细）
     * @throws com.gatekeeper.exception.GatewayException apiId 为空抛 400
     */
    ApiParamCheckResult checkRequired(Long apiId);
}
