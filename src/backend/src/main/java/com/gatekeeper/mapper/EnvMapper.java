package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.Env;
import org.apache.ibatis.annotations.Mapper;

/**
 * 环境表 Mapper — 负责 env 表的数据访问
 *
 * <p>T03a 环境主数据：env_code 一旦创建不可修改（架构 D1），是接口配置/授权/配额/
 * 凭证/IP 白名单横向贯穿的环境维度键。</p>
 *
 * @author GateKeeper
 * @since T03a (APIM V2)
 */
@Mapper
public interface EnvMapper extends BaseMapper<Env> {
}
