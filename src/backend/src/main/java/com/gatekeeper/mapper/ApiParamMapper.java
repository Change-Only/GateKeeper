package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.ApiParam;
import org.apache.ibatis.annotations.Mapper;

/**
 * 接口参数表 Mapper — 负责 api_param 表的数据访问
 *
 * <p>T03b 接口参数定义：入参/响应/错误码 元数据，支持 parent_id 嵌套结构。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Mapper
public interface ApiParamMapper extends BaseMapper<ApiParam> {
}
