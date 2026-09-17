package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.SysOperationLog;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 操作审计日志表 Mapper — 负责 sys_operation_log 表的数据访问
 */
public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {

    /**
     * 取审计表里**真实出现过**的操作模块去重值。
     *
     * <p>用途：给审计页「操作模块」下拉提供候选项（{@code GET /system/operation-log/filter-options}）。</p>
     *
     * <p><b>为什么必须查库而不是前端写死</b>：{@code operation_module} 的取值来自
     * {@code OperationLogAspect#firstSegment(requestURI)} —— 任何 controller 路径的**首段**
     * 都会被用作模块名（命中 {@code MODULE_MAP} 则取映射值，否则取首段大写）。
     * 也就是说值域**随 controller 数量增长**，写死必然过期
     * （2026-09-17 实测：前端硬编码 5 项，库里真实有 18 项 ⇒ 14 个值的记录筛不出来）。</p>
     */
    @Select("SELECT DISTINCT operation_module FROM sys_operation_log "
            + "WHERE operation_module IS NOT NULL AND operation_module <> '' "
            + "ORDER BY operation_module")
    List<String> selectDistinctModules();

    /**
     * 取审计表里**真实出现过**的操作类型去重值（{@code CREATE}/{@code UPDATE}/{@code DELETE}）。
     *
     * <p>同 {@link #selectDistinctModules()}：前端写死的 {@code LOGIN}/{@code LOGOUT}
     * 实际上**永远不会被写入** —— {@code OperationLogAspect} 只把 POST/PUT/DELETE
     * 映射成 CREATE/UPDATE/DELETE，且登录已被该切面排除。写死的两个死选项同样属于枚举漂移。</p>
     */
    @Select("SELECT DISTINCT operation_type FROM sys_operation_log "
            + "WHERE operation_type IS NOT NULL AND operation_type <> '' "
            + "ORDER BY operation_type")
    List<String> selectDistinctTypes();
}
