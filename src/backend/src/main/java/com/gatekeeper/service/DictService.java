package com.gatekeeper.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.SysDictDto;
import com.gatekeeper.dto.SysDictItemDto;
import com.gatekeeper.entity.SysDict;
import com.gatekeeper.entity.SysDictItem;

import java.util.List;

/**
 * 数据字典服务接口 — T05 sys-dict 字典管理能力
 *
 * <p>提供 sys_dict（字典头）与 sys_dict_item（字典项）的 CRUD：
 * <ul>
 *   <li>字典头与字典项分开维护，避免一次 PUT 全量替换丢失并发</li>
 *   <li>{@link #getWithItems} 返回字典头 + 字典项集合</li>
 *   <li>{@link #deleteDict} 级联清理字典项（无外键约束，手动避免孤儿数据）</li>
 *   <li>状态语义：0=停用, 1=启用</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
public interface DictService extends IService<SysDict> {

    /**
     * 分页查询字典列表。
     *
     * @param pageNum 当前页码（从 1 开始）
     * @param pageSize 每页条数
     * @param keyword 关键字（模糊匹配 dict_code/dict_name，可空）
     * @param status 状态筛选（可空）
     * @return 分页结果
     */
    PageResult<SysDict> pageQuery(int pageNum, int pageSize, String keyword, Integer status);
    /**
     * 查询某字典下的字典项列表。
     *
     * @param dictCode 字典编码
     * @return 字典项列表（按 sort_order 升序）
     */
    List<SysDictItem> listItems(String dictCode);

    /**
     * 新建字典（校验 dict_code 唯一）。
     *
     * @param dto 入参
     * @return 创建后的字典
     */
    SysDict createDict(SysDictDto dto);

    /**
     * 更新字典（id 必填，dictCode 不可重复）。
     *
     * @param dto 入参
     */
    void updateDict(SysDictDto dto);

    /**
     * 删除字典（级联删除字典项，built_in=1 拒绝）。
     *
     * @param id 字典 ID
     */
    void deleteDict(Long id);

    /**
     * 新增字典项（校验 dict_code+item_value 唯一）。
     *
     * @param dto 入参
     * @return 创建后的字典项
     */
    SysDictItem addItem(SysDictItemDto dto);

    /**
     * 更新字典项（id 必填）。
     *
     * @param dto 入参
     */
    void updateItem(SysDictItemDto dto);

    /**
     * 删除字典项。
     *
     * @param itemId 字典项 ID
     */
    void deleteItem(Long itemId);
}
