package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.EnvDto;
import com.gatekeeper.entity.Env;

import java.util.List;

/**
 * 环境服务接口 — T03a 主数据三大基础域之一
 *
 * <p>提供环境（env）的 CRUD + envCode 不可修改校验：
 * <ul>
 *   <li>{@link #pageQuery} 支持按 envCode 模糊、按 status 精确筛选</li>
 *   <li>{@link #listEnabled} 用于下拉选择器（前端每个表单都查）</li>
 *   <li>{@link #createEnv} 创建前校验 envCode 唯一</li>
 *   <li>{@link #updateEnv} envCode 一旦创建不可修改（架构 D1），
 *       若试图修改则抛业务异常；service 同时拒绝空 null 入参</li>
 *   <li>{@link #deleteEnv} 删/停用时不影响存量凭证/授权（架构 D1）</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
public interface EnvService extends IService<Env> {

    /**
     * 分页查询环境列表。
     *
     * @param pageNum  当前页码（从 1 开始）
     * @param pageSize 每页条数
     * @param envCode  环境编码模糊筛选（可空）
     * @param status   状态筛选（可空）
     * @return 分页结果
     */
    PageResult<EnvDto> pageQuery(int pageNum, int pageSize, String envCode, Integer status);

    /**
     * 查询所有启用状态的环境（下拉用）。
     *
     * <p>前端在每个表单都查，按 sortOrder 升序。</p>
     *
     * @return 启用环境列表
     */
    List<EnvDto> listEnabled();
    /**
     * 按 envCode 查询环境（用于内部关联 / 校验）。
     *
     * @param envCode 环境编码
     * @return 环境实体（不存在返回 null）
     */
    Env getByEnvCode(String envCode);

    /**
     * 新建环境。
     *
     * @param dto 入参（envCode 仅允许 dev/test/pre/prod，gatewayUrl 必填）
     * @return 创建后的环境（含主键）
     * @throws IllegalArgumentException envCode 已存在 / 字段非法
     */
    Env createEnv(EnvDto dto);

    /**
     * 更新环境信息（envCode 不允许修改）。
     *
     * @param id  环境 ID
     * @param dto 入参
     * @throws IllegalArgumentException 环境不存在 / envCode 被尝试修改
     */
    void updateEnv(Long id, EnvDto dto);

    /**
     * 删除环境。
     *
     * <p>删/停用不影响存量凭证/授权（架构 D1 决策）。</p>
     *
     * @param id 环境 ID
     * @throws IllegalArgumentException 环境不存在
     */
    void deleteEnv(Long id);
}
