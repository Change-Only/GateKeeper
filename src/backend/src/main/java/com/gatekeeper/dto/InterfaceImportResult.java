package com.gatekeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAPI 导入结果（T18）。
 *
 * <p>导入是"批量 + 部分成功"语义，所以结果必须能回答三个问题：
 * <b>文档是什么</b>（title/version/openapi）、<b>一共多少条</b>（total/imported/skipped/failed）、
 * <b>每条为什么没进来</b>（items 明细）。只有计数没有明细，用户拿到
 * "导入 12 条，成功 9 条"时无从下手。</p>
 *
 * <p>参数总数 {@link #paramCount} 单独统计：接口"导进来了"但参数一条没解析出来，
 * 是用户最需要立刻察觉的情况。</p>
 *
 * @author GateKeeper
 * @since T18 (OpenAPI 导入)
 */
@Data
public class InterfaceImportResult {

    /** 导入目标分组 id */
    private Long groupId;

    /** 导入目标分组名（回显，便于确认导对地方了） */
    private String groupName;

    /** 文档声明的 openapi 版本，如 3.0.1 */
    private String openapiVersion;

    /** info.title */
    private String title;

    /** info.version */
    private String specVersion;

    /**
     * 文档 servers[0].url —— <b>仅供提示</b>。
     *
     * <p>刻意不写进 {@code api_interface.backend_url}：OpenAPI 的 servers 是"对外基地址"，
     * 常常就是网关自己的域名，直接当转发目标会把流量打回网关（回环）。
     * 因此导入后后端地址一律留空，由用户按真实上游补全。</p>
     */
    private String serverUrl;

    /** 文档中识别到的 operation 总数 */
    private int total;

    /** 成功导入数 */
    private int imported;

    /** 跳过数（同路径+同方法已存在） */
    private int skipped;

    /** 失败数 */
    private int failed;

    /** 实际写入的参数总条数 */
    private int paramCount;

    /** 逐条明细 */
    private List<Item> items = new ArrayList<>();

    /** 全局提示（如"文档较大，仅导入前 500 个操作"） */
    private List<String> warnings = new ArrayList<>();

    /**
     * 单条接口的导入结果。
     */
    @Data
    public static class Item {

        /** 导入成功后的接口 id（跳过/失败时为 null） */
        private Long apiId;

        /** 接口名称 */
        private String name;

        /** HTTP 方法 */
        private String method;

        /** 接口路径 */
        private String path;

        /** 文档内定位串，如 paths./users.get */
        private String source;

        /** 结果：IMPORTED / SKIPPED / FAILED */
        private String result;

        /** 原因说明（跳过原因 / 失败原因；成功时可为空） */
        private String message;

        /** 该条写入的参数条数 */
        private int paramCount;

        /** 解析期提示（如分区截断） */
        private List<String> warnings = new ArrayList<>();

        /** 结果常量：已导入 */
        public static final String IMPORTED = "IMPORTED";
        /** 结果常量：已跳过 */
        public static final String SKIPPED = "SKIPPED";
        /** 结果常量：失败 */
        public static final String FAILED = "FAILED";
    }
}
