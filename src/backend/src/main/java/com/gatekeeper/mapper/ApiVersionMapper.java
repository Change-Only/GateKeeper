package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.ApiVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口版本表 Mapper — 负责 api_version 表的数据访问
 *
 * <p>T03b 接口版本：每个接口多版本，is_current 标记当前默认，配合 gray_ratio 数据面分流。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Mapper
public interface ApiVersionMapper extends BaseMapper<ApiVersion> {
}
