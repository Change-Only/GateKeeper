package com.gatekeeper.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 接口参数 JSON 导入结果 DTO — 校验失败时逐条返回不通过的明细
 *
 * <p>导入语义：先全量校验，<strong>全部通过才落库</strong>（原子）；
 * 任一条不通过则 {@code success=false} 且 {@code errors} 列出失败条目，
 * 数据库不发生任何变更。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Data
public class ApiParamImportResult {

    /** 是否导入成功（true=全部校验通过并已落库） */
    private boolean success;

    /** 实际导入条数（success=true 时有效） */
    private int imported;

    /** 校验失败明细（success=false 时非空） */
    private List<ImportError> errors = new ArrayList<>();

    /**
     * 单条导入错误。
     */
    @Data
    public static class ImportError {

        /** 所属分区：header/request/response/error */
        private String section;

        /** 分区内下标（从 0 开始） */
        private int index;

        /** 字段名（可空） */
        private String fieldName;

        /** 失败原因 */
        private String reason;

        /**
         * 构造一条导入错误。
         *
         * @param section   分区
         * @param index     下标
         * @param fieldName 字段名
         * @param reason    原因
         */
        public ImportError(String section, int index, String fieldName, String reason) {
            this.section = section;
            this.index = index;
            this.fieldName = fieldName;
            this.reason = reason;
        }

        /** Jackson 反序列化需要的无参构造。 */
        public ImportError() {
        }
    }
}
