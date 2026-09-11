package com.gatekeeper.vo;

import com.gatekeeper.entity.SysDict;
import com.gatekeeper.entity.SysDictItem;
import lombok.Data;

import java.util.List;

/**
 * 字典详情 VO — 字典头 + 字典项集合（D3 接口返回形状）
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Data
public class SysDictDetailVo {

    /** 字典头 */
    private SysDict dict;

    /** 字典项列表（按 sort_order 升序） */
    private List<SysDictItem> items;
}
