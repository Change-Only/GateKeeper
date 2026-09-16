package com.gatekeeper.dto;

import lombok.Data;

/**
 * OpenAPI 导入请求（T18）。
 *
 * <p><b>为什么用「groupId + 文档正文」而不是 multipart 文件上传</b>：
 * 前端用 {@code FileReader.readAsText} 把 .json/.yaml 读成文本再提交，有三个好处 ——
 * ① 后端只需处理 UTF-8 文本，不必再分辨 part 编码；
 * ② 「选择文件」与「粘贴内容」共用同一条链路，不必维护第二条上传通道；
 * ③ 探针可以纯 JSON 构造请求（multipart 探针易碎且难以断言）。
 * 代价是文档体积受请求体限制，故解析器侧对超过 8MB 的文档直接 400。</p>
 *
 * @author GateKeeper
 * @since T18 (OpenAPI 导入)
 */
@Data
public class InterfaceImportRequest {

    /**
     * 导入目标分组 id —— <b>必填</b>（需求硬约束：导入前必须先选择分组）。
     *
     * <p>不写 {@code @NotNull} 而由服务层显式判空并给出中文提示：
     * {@code @Valid} 的默认报错信息是 Bean Validation 的英文模板，
     * 而这个字段的缺失正是最常被触发的分支，值得一条能直接照做的提示。</p>
     */
    private Long groupId;

    /** 文档正文（UTF-8；JSON 或 YAML 皆可） */
    private String content;

    /**
     * 原始文件名，仅用于推断 JSON / YAML 解析器（可为空）。
     *
     * <p>即使后缀判断错了也不会失败：解析器会在首选 mapper 报错后换另一种重试，
     * 以容忍「把 JSON 存成 .yaml」这类常见失误。</p>
     */
    private String fileName;
}
