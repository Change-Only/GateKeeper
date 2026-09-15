package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gatekeeper.dto.DataScopeItemDto;
import com.gatekeeper.dto.RoleDataScopeSaveDto;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.Env;
import com.gatekeeper.entity.SysRole;
import com.gatekeeper.entity.SysRoleDataScope;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.mapper.EnvMapper;
import com.gatekeeper.mapper.SysRoleDataScopeMapper;
import com.gatekeeper.mapper.SysRoleMapper;
import com.gatekeeper.service.RoleDataScopeService;
import com.gatekeeper.vo.DataScopeOptionsVo;
import com.gatekeeper.vo.RoleSimpleVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 角色数据权限服务实现 — T05 perm-datascope 数据权限能力
 *
 * <p>实现要点：
 * <ul>
 *   <li>角色列表的 dataScope 取自 sys_role.data_scope（实体未映射该列，使用 selectMaps 读取原始列）</li>
 *   <li>接口分组的 groupCode 同理（实体未映射，使用 selectMaps）</li>
 *   <li>{@link #saveRoleScopes} 全量覆盖：先 DELETE 该角色旧范围，再批量 INSERT dto.scopes（空=不限）</li>
 * </ul></p>
 *
 * <p>T15：业务线维度已整体下线 —— 选项不再返回 {@code bizLines}，
 * {@code sys_role_datascope} 表与其 {@code scope_type} 枚举值保留（只加不删，历史数据兼容）。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleDataScopeServiceImpl implements RoleDataScopeService {

    private final SysRoleDataScopeMapper scopeMapper;
    private final SysRoleMapper sysRoleMapper;
    private final EnvMapper envMapper;
    private final ApiGroupMapper apiGroupMapper;

    @Override
    public List<RoleSimpleVo> listRolesForSelector(String keyword) {
        QueryWrapper<SysRole> wrapper = new QueryWrapper<>();
        wrapper.select("id", "role_name", "data_scope");
        if (StringUtils.hasText(keyword)) {
            wrapper.like("role_name", keyword);
        }
        wrapper.orderByAsc("id");
        List<Map<String, Object>> maps = sysRoleMapper.selectMaps(wrapper);
        List<RoleSimpleVo> result = new ArrayList<>(maps.size());
        for (Map<String, Object> m : maps) {
            RoleSimpleVo vo = new RoleSimpleVo();
            vo.setRoleId(toLong(m.get("id")));
            vo.setRoleName((String) m.get("role_name"));
            vo.setDataScope((String) m.get("data_scope"));
            result.add(vo);
        }
        return result;
    }

    @Override
    public DataScopeOptionsVo listOptions() {
        DataScopeOptionsVo vo = new DataScopeOptionsVo();

        // 环境
        List<Env> envs = envMapper.selectList(new QueryWrapper<Env>().orderByAsc("id"));
        List<DataScopeOptionsVo.EnvSimple> envList = new ArrayList<>(envs.size());
        for (Env e : envs) {
            DataScopeOptionsVo.EnvSimple s = new DataScopeOptionsVo.EnvSimple();
            s.setEnvCode(e.getEnvCode());
            s.setEnvName(e.getEnvName());
            s.setId(e.getId());
            envList.add(s);
        }
        vo.setEnvs(envList);

        // 接口分组（group_code 未映射到实体，用 selectMaps 读取原始列）
        QueryWrapper<ApiGroup> agWrapper = new QueryWrapper<>();
        agWrapper.select("id", "group_code", "group_name").orderByAsc("id");
        List<Map<String, Object>> agMaps = apiGroupMapper.selectMaps(agWrapper);
        List<DataScopeOptionsVo.ApiGroupSimple> agList = new ArrayList<>(agMaps.size());
        for (Map<String, Object> m : agMaps) {
            DataScopeOptionsVo.ApiGroupSimple s = new DataScopeOptionsVo.ApiGroupSimple();
            s.setId(toLong(m.get("id")));
            s.setGroupCode((String) m.get("group_code"));
            s.setGroupName((String) m.get("group_name"));
            agList.add(s);
        }
        vo.setApiGroups(agList);
        return vo;
    }

    @Override
    public List<SysRoleDataScope> listByRole(Long roleId) {
        if (roleId == null) {
            return new ArrayList<>();
        }
        return scopeMapper.selectList(
                new QueryWrapper<SysRoleDataScope>().eq("role_id", roleId).orderByAsc("id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveRoleScopes(RoleDataScopeSaveDto dto) {
        if (dto == null || dto.getRoleId() == null) {
            throw GatewayException.badRequest("角色ID不能为空");
        }
        // 全量覆盖：先删后插
        scopeMapper.delete(new QueryWrapper<SysRoleDataScope>().eq("role_id", dto.getRoleId()));
        if (dto.getScopes() != null) {
            for (DataScopeItemDto item : dto.getScopes()) {
                if (item == null) {
                    continue;
                }
                SysRoleDataScope scope = new SysRoleDataScope();
                scope.setRoleId(dto.getRoleId());
                scope.setScopeType(item.getScopeType());
                scope.setScopeValue(item.getScopeValue());
                scope.setCreatedAt(LocalDateTime.now());
                scopeMapper.insert(scope);
            }
        }
        log.info("Role data scopes saved: roleId={}, scopeCount={}",
                dto.getRoleId(), dto.getScopes() == null ? 0 : dto.getScopes().size());
    }

    /**
     * 将 DB 返回的列值安全转为 Long（兼容 Integer / 字符串）。
     */
    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
