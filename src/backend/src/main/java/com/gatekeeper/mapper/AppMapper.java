package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.App;
import org.apache.ibatis.annotations.Mapper;

/**
 * 应用表 Mapper — 负责 app 表的数据访问
 *
 * <p>T15：原 {@code countByLineId}（业务线删除前的引用检查）随业务线模块一并移除。</p>
 */
@Mapper
public interface AppMapper extends BaseMapper<App> {
}
