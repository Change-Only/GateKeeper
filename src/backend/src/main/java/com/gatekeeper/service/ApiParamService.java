package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.entity.ApiParam;

import java.util.List;

/**
 * 接口参数服务接口 — T03b 接口生命周期子资源之一
 *
 * <p>提供接口参数（api_param）的 CRUD + 树形结构：
 * <ul>
 *   <li>{@link #list} 按 apiId / paramType / parentId 筛选</li>
 *   <li>{@link #tree} 基于 parent_id 构建嵌套树（root = parentId 0）</li>
 *   <li>{@link #get} 详情（不存在抛 404）</li>
 *   <li>{@link #create} 创建（apiId / fieldName / paramType 必填）</li>
 *   <li>{@link #update} 仅修改可编辑字段（不影响 apiId / parentId / paramType 结构键）</li>
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
}
