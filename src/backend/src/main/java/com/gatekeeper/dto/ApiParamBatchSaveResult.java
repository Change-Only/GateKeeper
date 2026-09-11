package com.gatekeeper.dto;

import lombok.Data;

/**
 * 接口参数批量保存结果 DTO — 返回各分区实际写入条数
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Data
public class ApiParamBatchSaveResult {

    /** Header 分区写入条数（paramType=1；未提交该分区时为 -1，表示"未变更"） */
    private int header = -1;

    /** Request 分区写入条数（paramType=3） */
    private int request = -1;

    /** Response 分区写入条数（paramType=4） */
    private int response = -1;

    /** Error 分区写入条数（paramType=5） */
    private int error = -1;

    /** 本次写入总条数 */
    private int total;
}
