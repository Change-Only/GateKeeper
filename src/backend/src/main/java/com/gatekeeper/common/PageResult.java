package com.gatekeeper.common;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页查询结果包装类
 * 用于列表类接口返回分页数据，包含当前页记录与分页元信息
 */
@Data
public class PageResult<T> implements Serializable {

    /** 当前页数据列表 */
    private List<T> records;

    /** 总记录数 */
    private long total;

    /** 当前页码 */
    private long current;

    /** 每页条数 */
    private long size;

    /**
     * 构造分页结果对象
     *
     * @param records 当前页数据列表
     * @param total   总记录数
     * @param current 当前页码
     * @param size    每页条数
     * @return 分页结果对象
     */
    public static <T> PageResult<T> of(List<T> records, long total, long current, long size) {
        PageResult<T> r = new PageResult<>();
        r.setRecords(records);
        r.setTotal(total);
        r.setCurrent(current);
        r.setSize(size);
        return r;
    }
}
