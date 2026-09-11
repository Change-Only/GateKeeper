package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.dto.ApiChangeLogDto;
import com.gatekeeper.entity.ApiChangeLog;

import java.util.List;

/**
 * 接口变更历史服务接口 — T03b 接口生命周期子资源之一（追加型，只读）
 *
 * <p>提供接口变更历史（api_change_log）的查询 + 追加：
 * <ul>
 *   <li>{@link #list} 按 apiId / changeType 筛选</li>
 *   <li>{@link #get} 详情（不存在抛 404）</li>
 *   <li>{@link #append} 追加一条变更记录（createTime=now，不允许修改/删除）</li>
 * </ul></p>
 *
 * <p>设计约束：变更历史为审计凭证，仅追加，无 update / delete 端点。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
public interface ApiChangeLogService extends IService<ApiChangeLog> {

    /**
     * 按接口ID / 变更类型筛选变更历史列表。
     *
     * @param apiId      接口ID（可空）
     * @param changeType 变更类型（可空）
     * @return 变更历史 DTO 列表（按 create_time 倒序）
     */
    List<ApiChangeLogDto> list(Long apiId, String changeType);

    /**
     * 按主键查询变更历史详情。
     *
     * @param id 记录 ID
     * @return 变更历史 DTO（不存在抛 404）
     */
    ApiChangeLogDto get(Long id);

    /**
     * 追加一条变更历史（createTime=now）。
     *
     * @param dto 入参（apiId / changeType 必填）
     * @return 新建记录 DTO（含主键与 createTime）
     * @throws com.gatekeeper.exception.GatewayException 必填缺失抛 400
     */
    ApiChangeLogDto append(ApiChangeLogDto dto);
}
