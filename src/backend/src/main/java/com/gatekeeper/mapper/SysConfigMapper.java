package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统参数配置表 Mapper — 负责 sys_config 表的数据访问
 *
 * <p>T05 配置管理（sys-config 页面后端）：承载参数配置 CRUD。
 * 列名 {@code sensitive} 为 MySQL 8 保留字，实体已用反引号映射，本 Mapper 无需额外处理。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}
