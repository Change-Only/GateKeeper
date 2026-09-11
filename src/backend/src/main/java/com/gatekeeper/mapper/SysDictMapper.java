package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SysDict;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据字典表 Mapper — 负责 sys_dict 表的数据访问
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Mapper
public interface SysDictMapper extends BaseMapper<SysDict> {
}
