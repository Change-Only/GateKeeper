package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.OperationLogOptionsVo;
import com.gatekeeper.entity.SysOperationLog;
import com.gatekeeper.mapper.SysOperationLogMapper;
import com.gatekeeper.service.SysOperationLogService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 操作审计日志服务实现 — 负责后台操作审计日志的分页查询，
 * 支持按操作类型与操作模块过滤。
 */
@Service
public class SysOperationLogServiceImpl extends ServiceImpl<SysOperationLogMapper, SysOperationLog>
        implements SysOperationLogService {

    /**
     * 操作类型下拉的**推荐序**：CREATE → UPDATE → DELETE（读起来符合"增改删"直觉）。
     * 不在此列的取值一律追加在后面（按库返回的字典序），保证新增类型不会漏在列表外。
     */
    private static final List<String> TYPE_PREFERRED_ORDER =
            Arrays.asList("CREATE", "UPDATE", "DELETE");

    /**
     * 分页查询操作审计日志，支持按操作类型与操作模块筛选
     *
     * @param current         当前页码
     * @param size            每页条数
     * @param operationType   操作类型（精确匹配，可为空）
     * @param operationModule 操作模块（精确匹配，可为空）
     * @return 操作日志分页结果
     */
    @Override
    public PageResult<SysOperationLog> pageQuery(int current, int size, String operationType, String operationModule) {
        Page<SysOperationLog> page = new Page<>(current, size);
        QueryWrapper<SysOperationLog> wrapper = new QueryWrapper<>();
        if (operationType != null && !operationType.isEmpty()) {
            wrapper.eq("operation_type", operationType); // 按操作类型过滤
        }
        if (operationModule != null && !operationModule.isEmpty()) {
            wrapper.eq("operation_module", operationModule); // 按操作模块过滤
        }
        wrapper.orderByDesc("created_at"); // 按创建时间倒序
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 审计页筛选下拉候选项。
     *
     * <p>实现要点（都为可测的纯逻辑，见 {@code SysOperationLogOptionsTest}）：</p>
     * <ol>
     *   <li><b>只回库里真实存在的值</b> —— 用 DISTINCT 查库，不合成"理论值域"，
     *       这样"下拉里有的就一定能筛出记录"；</li>
     *   <li><b>再兜一层去重 + 剔空</b> —— 不依赖 SQL 的 DISTINCT 一定干净
     *       （历史行可能有前后空格 / 空串），避免下拉里出现两个看起来一样的选项；</li>
     *   <li><b>types 用语义序</b>，未知类型追加在尾部，不丢值。</li>
     * </ol>
     *
     * @return 两字段均非 null（无数据时为空列表，前端据此回退到本地兜底常量）
     */
    @Override
    public OperationLogOptionsVo filterOptions() {
        OperationLogOptionsVo vo = new OperationLogOptionsVo();
        vo.setModules(normalize(baseMapper.selectDistinctModules(), false));
        vo.setTypes(normalize(baseMapper.selectDistinctTypes(), true));
        return vo;
    }

    /**
     * 归一化候选值：去空、去首尾空白、去重（保持入参顺序）。
     *
     * @param raw      库返回的原始值（可能含 null / 空串 / 重复）
     * @param byType   是否按 {@link #TYPE_PREFERRED_ORDER} 重排（未知值追加在尾部）
     */
    private List<String> normalize(List<String> raw, boolean byType) {
        Set<String> shown = new LinkedHashSet<>();
        if (raw != null) {
            for (String v : raw) {
                if (v == null) {
                    continue;
                }
                String t = v.trim();
                if (!t.isEmpty()) {
                    shown.add(t);
                }
            }
        }
        if (!byType) {
            return new ArrayList<>(shown);
        }
        List<String> ordered = new ArrayList<>();
        for (String preferred : TYPE_PREFERRED_ORDER) {
            if (shown.remove(preferred)) {   // remove 返回 true 表示确实存在
                ordered.add(preferred);
            }
        }
        List<String> rest = new ArrayList<>(shown);
        Collections.sort(rest);              // 未知类型保持确定性顺序，不靠库的返回序
        ordered.addAll(rest);
        return ordered;
    }
}
