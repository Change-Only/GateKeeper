package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SysDictItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据字典项表 Mapper — 负责 sys_dict_item 表的数据访问
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Mapper
public interface SysDictItemMapper extends BaseMapper<SysDictItem> {
}
