package com.gatekeeper.vo;

import lombok.Data;

import java.util.List;

/**
 * 数据权限选项 VO — 角色数据范围配置页的下拉选项（S2 接口返回形状）
 *
 * <p>环境 / 接口分组两类主数据选项，供前端配置范围时选择。
 * 接口分组使用 {@code groupCode}（实体未映射，由 Service 通过 selectMaps 读取）。</p>
 *
 * <p>T15：业务线维度已整体下线（业务线主数据模块删除），故本 VO 不再返回 {@code bizLines}。</p>
 *
 * @author GateKeeper
 * @since T05 (APIM V2)
 */
@Data
public class DataScopeOptionsVo {

    /** 环境选项 */
    private List<EnvSimple> envs;

    /** 接口分组选项 */
    private List<ApiGroupSimple> apiGroups;

    /** 环境精简项（envCode / envName / id） */
    @Data
    public static class EnvSimple {
        private String envCode;
        private String envName;
        private Long id;
    }

    /** 接口分组精简项（id / groupCode / groupName） */
    @Data
    public static class ApiGroupSimple {
        private Long id;
        private String groupCode;
        private String groupName;
    }
}
