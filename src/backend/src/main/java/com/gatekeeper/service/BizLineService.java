package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.BizLineDto;
import com.gatekeeper.entity.BizLine;

import java.util.List;

/**
 * 业务线服务接口 — T03a 主数据三大基础域之一
 *
 * <p>提供业务线（biz_line）的 CRUD + 引用校验：
 * <ul>
 *   <li>{@link #pageQuery} 支持按 lineCode / lineName 模糊、按 status 精确筛选</li>
 *   <li>{@link #listEnabled} 用于下拉选择器（全量启用中）</li>
 *   <li>{@link #createBizLine} 创建前校验 lineCode 唯一</li>
 *   <li>{@link #updateBizLine} 仅允许改 status / lineName / ownerName / memberCount / remark，
 *       lineCode 不可修改（避免历史引用混乱）</li>
 *   <li>{@link #deleteBizLine} 有应用引用时拒绝并提示</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
public interface BizLineService extends IService<BizLine> {

    /**
     * 分页查询业务线列表。
     *
     * @param pageNum  当前页码（从 1 开始）
     * @param pageSize 每页条数
     * @param keyword  关键字（模糊匹配 lineCode 或 lineName，可空）
     * @param status   状态筛选（可空）
     * @return 分页结果
     */
    PageResult<BizLineDto> pageQuery(int pageNum, int pageSize, String keyword, Integer status);

    /**
     * 查询所有启用状态的业务线（下拉用）。
     *
     * @return 启用业务线列表，按 id 升序
     */
    List<BizLineDto> listEnabled();

    /**
     * 按主键查询业务线详情。
     *
     * @param id 业务线 ID
     * @return 业务线实体（不存在返回 null）
     */
    BizLine getBizLine(Long id);

    /**
     * 新建业务线。
     *
     * @param dto 入参（lineCode/lineName 必填）
     * @return 创建后的业务线（含主键）
     * @throws IllegalArgumentException lineCode 已存在 / 字段非法
     */
    BizLine createBizLine(BizLineDto dto);

    /**
     * 更新业务线基本信息（lineCode 不允许修改）。
     *
     * @param id  业务线 ID
     * @param dto 入参
     * @throws IllegalArgumentException 业务线不存在 / lineCode 被尝试修改
     */
    void updateBizLine(Long id, BizLineDto dto);

    /**
     * 删除业务线：若存在应用引用则拒绝。
     *
     * @param id 业务线 ID
     * @throws IllegalArgumentException 业务线不存在 / 仍有应用引用
     */
    void deleteBizLine(Long id);
}
