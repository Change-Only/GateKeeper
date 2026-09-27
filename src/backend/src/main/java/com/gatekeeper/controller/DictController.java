package com.gatekeeper.controller;

import com.gatekeeper.common.PageResult;
import com.gatekeeper.common.Result;
import com.gatekeeper.dto.SysDictDto;
import com.gatekeeper.dto.SysDictItemDto;
import com.gatekeeper.entity.SysDict;
import com.gatekeeper.entity.SysDictItem;
import com.gatekeeper.security.RequirePerm;
import com.gatekeeper.service.DictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 数据字典 Controller — T05 sys-dict 字典管理对外能力
 *
 * <p>接口路径（context-path=/api）：
 * <ul>
 *   <li>GET    /api/dict/list                字典分页</li>
 *   <li>GET    /api/dict/all                 全量（下拉）</li>
 *   <li>GET    /api/dict/{dictCode}          字典详情（含项）</li>
 *   <li>GET    /api/dict/{dictCode}/items    字典项列表</li>
 *   <li>POST   /api/dict/create              新建字典（高危写，sys:dict:update）</li>
 *   <li>PUT    /api/dict/update              更新字典（高危写，sys:dict:update）</li>
 *   <li>DELETE /api/dict/{id}                删除字典（高危写，sys:dict:update，built_in=1 拒绝）</li>
 *   <li>POST   /api/dict/{dictCode}/items    新增字典项（高危写，sys:dict:update）</li>
 *   <li>PUT    /api/dict/items/{itemId}      更新字典项（高危写，sys:dict:update）</li>
 *   <li>DELETE /api/dict/items/{itemId}      删除字典项（高危写，sys:dict:update）</li>
 * </ul></p>
 *
 * <p>说明：字典头（dictCode/dictName）与字典项（items）分开维护，避免一次 PUT 全量替换丢失并发。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@RestController
@RequestMapping("/dict")
@RequiredArgsConstructor
@Tag(name = "数据字典", description = "数据字典与字典项管理")
public class DictController {

    private final DictService dictService;

    /**
     * 分页查询字典。
     */
    @Operation(summary = "分页查询字典")
    @GetMapping("/list")
    public Result<PageResult<SysDict>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return Result.success(dictService.pageQuery(pageNum, pageSize, keyword, status));
    }
    /**
     * 字典项列表。
     */
    @Operation(summary = "字典项列表")
    @GetMapping("/{dictCode}/items")
    public Result<List<SysDictItem>> items(@PathVariable String dictCode) {
        return Result.success(dictService.listItems(dictCode));
    }

    /**
     * 新建字典（高危写）。
     */
    @RequirePerm(value = "sys:dict:update")
    @Operation(summary = "新建字典")
    @PostMapping("/create")
    public Result<SysDict> create(@Valid @RequestBody SysDictDto dto) {
        return Result.success(dictService.createDict(dto));
    }

    /**
     * 更新字典（高危写）。
     */
    @RequirePerm(value = "sys:dict:update")
    @Operation(summary = "更新字典")
    @PutMapping("/update")
    public Result<Void> update(@Valid @RequestBody SysDictDto dto) {
        dictService.updateDict(dto);
        return Result.success();
    }

    /**
     * 删除字典（高危写，built_in=1 拒绝）。
     */
    @RequirePerm(value = "sys:dict:update")
    @Operation(summary = "删除字典")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dictService.deleteDict(id);
        return Result.success();
    }

    /**
     * 新增字典项（高危写）。dictCode 取自路径。
     */
    @RequirePerm(value = "sys:dict:update")
    @Operation(summary = "新增字典项")
    @PostMapping("/{dictCode}/items")
    public Result<SysDictItem> addItem(@PathVariable String dictCode, @RequestBody SysDictItemDto dto) {
        if (dto == null) {
            dto = new SysDictItemDto();
        }
        dto.setDictCode(dictCode);
        return Result.success(dictService.addItem(dto));
    }

    /**
     * 更新字典项（高危写）。itemId 取自路径。
     */
    @RequirePerm(value = "sys:dict:update")
    @Operation(summary = "更新字典项")
    @PutMapping("/items/{itemId}")
    public Result<Void> updateItem(@PathVariable Long itemId, @RequestBody SysDictItemDto dto) {
        if (dto == null) {
            dto = new SysDictItemDto();
        }
        dto.setId(itemId);
        dictService.updateItem(dto);
        return Result.success();
    }

    /**
     * 删除字典项（高危写）。
     */
    @RequirePerm(value = "sys:dict:update")
    @Operation(summary = "删除字典项")
    @DeleteMapping("/items/{itemId}")
    public Result<Void> deleteItem(@PathVariable Long itemId) {
        dictService.deleteItem(itemId);
        return Result.success();
    }
}
