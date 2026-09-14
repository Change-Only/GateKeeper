package com.gatekeeper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口分组环境配置表（api_group_env_config） — T13
 *
 * <p>需求（2026-09-14 用户第 4 条）：环境配置从「接口侧」下沉到「接口分组侧」，
 * 并支持**沿分组树向上继承父级配置**。维护点因此唯一（分组页），接口侧只读展示生效结果。</p>
 *
 * <p>与老表 {@link ApiEnvConfig}（接口级）的关系：
 * <ul>
 *   <li>老表一行不动，作为**接口级覆盖**保留；网关解析优先级
 *       <b>接口级覆盖 &gt; 分组继承 &gt; {@code api_interface.backend_url} 兜底</b>。</li>
 *   <li>本表**刻意没有 version 列**：老表的 {@code uk_api_env_ver(api_id, env_code, version)}
 *       因 version 可空而形同虚设（MySQL 唯一索引对 NULL 不生效，同 (api_id, env_code) 可塞多条）。
 *       分组配置不需要版本维度，直接从表结构上不给这个口子 —— 唯一键是 (group_id, env_code)。</li>
 * </ul></p>
 *
 * <p>继承语义：**整体继承**（前缀 / 连接超时 / 读取超时 / 重试 / Mock 一起来），不做逐字段合并。
 * 解析由 {@code com.gatekeeper.gateway.EnvConfigResolver} 统一负责，避免各处自己走父链。</p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
@Data
@TableName("api_group_env_config")
public class ApiGroupEnvConfig {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接口分组ID（关联 api_group.id） */
    private Long groupId;

    /** 环境编码（dev / test / pre / prod） */
    private String envCode;

    /** 服务前缀（不含 URI；完整后端地址 = 服务前缀 + api_interface.interface_path） */
    private String upstreamUrl;

    /** 连接超时(ms) */
    private Integer connectTimeout;

    /** 读取超时(ms) */
    private Integer readTimeout;

    /** 重试次数（写操作接口应保持 0，防重复提交） */
    private Integer retryCount;

    /** 1=开启Mock（网关短路、不转发），0=关闭 */
    private Integer mockEnabled;

    /** Mock 返回的 HTTP 状态码（默认 200） */
    private Integer mockStatus;

    /** Mock 返回体（留空 = 返回默认提示 JSON） */
    private String mockResponse;

    /** 1=已配置, 2=已验证（连通性测试通过） */
    private Integer configStatus;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
