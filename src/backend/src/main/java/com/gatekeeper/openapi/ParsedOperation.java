package com.gatekeeper.openapi;

import com.gatekeeper.dto.ApiParamDto;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI 解析产物 —— 一个「接口 + 它的 4 类参数」的中间表示（T18）。
 *
 * <p><b>为什么需要这层中间表示</b>：解析（把 spec 结构读成 Java 对象）与落库
 * （创建 ApiInterface + 写 api_param）是两件独立的事，失败语义也不同 ——
 * 解析失败是「文档问题」（整批 400），落库失败是「某一条接口的问题」（该条计入失败、其余继续）。
 * 拆开后解析器可以脱离 DB 单测，导入服务也可以只测编排。</p>
 *
 * <p>参数分区与 {@code api_param.param_type} 一一对应：
 * header=1 / request=3 / response=4 / error=5。</p>
 *
 * @author GateKeeper
 * @since T18 (OpenAPI 导入)
 */
@Data
public class ParsedOperation {

    /** HTTP 方法（大写；已限定在 api_interface.request_method 的合法集合内） */
    private String method;

    /** 网关路径（取自 spec 的 paths key，已按加密后列宽截断保护） */
    private String path;

    /** 接口名称（summary → operationId → "METHOD path" 三级回退） */
    private String name;

    /** 接口描述 */
    private String description;

    /** 入参类型：JSON / FORM / QUERY */
    private String requestParamType;

    /**
     * 文档内定位串（如 {@code paths./users.get}）——
     * 只用于失败明细，让用户能在 spec 里一眼找到出问题的那一条。
     */
    private String sourceLocation;

    /** Header 参数（paramType=1） */
    private final List<ApiParamDto> header = new ArrayList<>();

    /** Request 入参（paramType=3） */
    private final List<ApiParamDto> request = new ArrayList<>();

    /** Response 出参（paramType=4） */
    private final List<ApiParamDto> response = new ArrayList<>();

    /** Error 错误码（paramType=5） */
    private final List<ApiParamDto> error = new ArrayList<>();

    /**
     * 解析过程中的非致命提示（如"分区超过上限已截断"、"body 只有描述没有属性"）。
     *
     * <p>不抛异常：这些情况都"导得进去但不完整"，用提示让用户知道差在哪，
     * 比直接失败或静默丢弃都有用。</p>
     */
    private final List<String> warnings = new ArrayList<>();

    /**
     * 该 operation 解析出的参数总数。
     *
     * @return 四类参数条数之和
     */
    public int paramCount() {
        return header.size() + request.size() + response.size() + error.size();
    }
}
