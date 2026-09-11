package com.gatekeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 接口必填参数校验结果 DTO — 前端「发布前检查」使用
 *
 * <p>校验规则（发布就绪度）：
 * <ul>
 *   <li>所有 {@code required=1} 的参数必须有非空 {@code fieldType}（类型契约完整）</li>
 *   <li>错误码分区（paramType=5）的每条必须定义 {@code errorCode}</li>
 *   <li>接口至少应定义一条 Request 入参（paramType=3）或显式声明无入参</li>
 * </ul>
 * 任一条不满足即 {@code passed=false} 并逐条给出 {@code issues}。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Data
public class ApiParamCheckResult {

    /** 校验是否通过 */
    private boolean passed;

    /** 参与校验的参数总数 */
    private int total;

    /** 其中必填参数数量 */
    private int requiredCount;

    /** 未通过项明细 */
    private List<Issue> issues = new ArrayList<>();

    /**
     * 单条校验问题。
     */
    @Data
    public static class Issue {

        /** 参数ID（可空） */
        private Long paramId;

        /** 所属分区：header/request/response/error */
        private String section;

        /** 字段名 */
        private String fieldName;

        /** 问题原因 */
        private String reason;

        /**
         * 构造一条校验问题。
         *
         * @param paramId   参数ID
         * @param section   分区
         * @param fieldName 字段名
         * @param reason    原因
         */
        public Issue(Long paramId, String section, String fieldName, String reason) {
            this.paramId = paramId;
            this.section = section;
            this.fieldName = fieldName;
            this.reason = reason;
        }

        /** Jackson 反序列化需要的无参构造。 */
        public Issue() {
        }
    }
}
