package com.gatekeeper.dto;

import lombok.Data;

import java.util.List;

/**
 * 接口参数批量保存请求 DTO — T03b 参数契约批量维护入口
 *
 * <p>一次提交某接口「整块」参数契约：按分区（header/request/response/error）
 * 全量替换。服务端对每个非 null 的分区执行「先删后插」，保证同一接口同一
 * paramType 的原子全量替换（事务内），避免逐条录入产生的中间态。</p>
 *
 * <p>分区与 {@code api_param.param_type} 的映射：
 * <ul>
 *   <li>{@code header}   → paramType=1（HEADER）</li>
 *   <li>{@code request}  → paramType=3（BODY，即入参）</li>
 *   <li>{@code response} → paramType=4（RESPONSE，出参）</li>
 *   <li>{@code error}    → paramType=5（ERROR_CODE，错误码）</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Data
public class ApiParamBatchSaveRequest {

    /** 接口ID（也可由 URL 查询串传入，二者取其一，body 优先） */
    private Long apiId;

    /** Header 参数（paramType=1） */
    private List<ApiParamDto> header;

    /** Request 入参（paramType=3） */
    private List<ApiParamDto> request;

    /** Response 出参（paramType=4） */
    private List<ApiParamDto> response;

    /** Error 错误码（paramType=5） */
    private List<ApiParamDto> error;
}
