package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.ApiInterface;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口表 Mapper — 负责 api_interface 表的数据访问
 */
@Mapper
public interface ApiInterfaceMapper extends BaseMapper<ApiInterface> {
}
