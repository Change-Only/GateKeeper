package com.gatekeeper.dto;

import com.gatekeeper.entity.ApiInterface;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 接口详情聚合 VO — T03b 升级后的 /interface/{id} 返回对象
 *
 * <p>一次返回「接口资产」的全貌，供前端接口详情页 4 个 Tab 免二次请求：
 * <ul>
 *   <li>{@code api}         基本信息（含 publish_status / current_version 等）</li>
 *   <li>{@code header}      参数契约 · Header（paramType=1）</li>
 *   <li>{@code request}     参数契约 · Request 入参（paramType=3）</li>
 *   <li>{@code response}    参数契约 · Response 出参（paramType=4）</li>
 *   <li>{@code error}       参数契约 · 错误码（paramType=5）</li>
 *   <li>{@code versions}    版本列表</li>
 *   <li>{@code envConfigs}  4 套环境配置</li>
 *   <li>{@code recentChangeLogs} 最近 5 条变更历史</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Data
public class InterfaceDetailVo {

    /** 基本信息 */
    private ApiInterface api;

    /** Header 参数（paramType=1） */
    private List<ApiParamDto> header = new ArrayList<>();

    /** Request 入参（paramType=3） */
    private List<ApiParamDto> request = new ArrayList<>();

    /** Response 出参（paramType=4） */
    private List<ApiParamDto> response = new ArrayList<>();

    /** 错误码（paramType=5） */
    private List<ApiParamDto> error = new ArrayList<>();

    /** 版本列表 */
    private List<ApiVersionDto> versions = new ArrayList<>();

    /** 环境配置（dev/test/pre/prod） */
    private List<ApiEnvConfigDto> envConfigs = new ArrayList<>();

    /** 最近 5 条变更历史（按 create_time 倒序） */
    private List<ApiChangeLogDto> recentChangeLogs = new ArrayList<>();
}
