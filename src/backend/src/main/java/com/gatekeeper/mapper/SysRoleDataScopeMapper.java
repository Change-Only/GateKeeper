package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SysRoleDataScope;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色数据权限范围表 Mapper — 负责 sys_role_datascope 表的数据访问
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Mapper
public interface SysRoleDataScopeMapper extends BaseMapper<SysRoleDataScope> {
}
