package com.gatekeeper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gatekeeper.entity.App;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 应用表 Mapper — 负责 app 表的数据访问
 */
@Mapper
public interface AppMapper extends BaseMapper<App> {

    /**
     * 统计指定业务线下应用数量（用于业务线删除前的引用检查）。
     *
     * <p>采用注解 SQL 避免新增 XML 文件，命中 idx_app_line 索引。
     * T03a 新增，业务线删除前置校验：{@code count > 0} 时拒绝删除。</p>
     *
     * @param lineId 业务线 ID
     * @return 该业务线下应用数（含停用、未删除的）
     */
    @Select("SELECT COUNT(*) FROM app WHERE line_id = #{lineId}")
    int countByLineId(Long lineId);
}
