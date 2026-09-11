package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.BizLine;
import org.apache.ibatis.annotations.Mapper;

/**
 * 业务线表 Mapper — 负责 biz_line 表的数据访问
 *
 * <p>T03a 业务线主数据：承载 app / api_interface / api_group / sys_user 的 line_id 外键。
 * 删除业务线前必须先校验无应用引用（由 Service 层调用 AppMapper.countByLineId）。</p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@Mapper
public interface BizLineMapper extends BaseMapper<BizLine> {
}
