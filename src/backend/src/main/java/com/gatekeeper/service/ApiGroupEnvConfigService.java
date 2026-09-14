package com.gatekeeper.service;

import com.gatekeeper.dto.ApiGroupEnvConfigDto;
import com.gatekeeper.gateway.dto.EffectiveEnvConfig;

import java.util.List;

/**
 * 接口分组环境配置服务 — T13
 *
 * <p>环境配置从「接口侧」下沉到「接口分组侧」：维护点唯一（分组页），
 * 接口侧只读展示生效结果；分组树**向上继承**（见 {@code EnvConfigResolver}）。</p>
 *
 * <p>与接口级配置的区别：
 * <ul>
 *   <li>粒度：一个分组 + 一个环境只有一条配置（唯一键 group_id+env_code），没有 version 维度；</li>
 *   <li>写入方式：以 {@link #upsert} 为主 —— 页面是"每个环境一行，填了就存、清空就删"的形态，
 *       upsert 比 create/update 两种入口更贴合，也天然幂等；</li>
 *   <li>Mock：新增 mockStatus/mockResponse，开启后网关**短路返回**配置报文（不再是"只存不用"）。</li>
 * </ul></p>
 *
 * @author GateKeeper
 * @since T13 (2026-09-14)
 */
public interface ApiGroupEnvConfigService {

    /** 四个环境（顺序与前端 ENV_LIST 一致，用于"每个环境一行"的预览） */
    List<String> ENVS = java.util.Arrays.asList("dev", "test", "pre", "prod");

    /**
     * 查询分组自己的环境配置（不含继承来的）。
     *
     * @param groupId 分组 ID（必填）
     * @param envCode 环境编码（可选，空则不过滤）
     * @return 配置列表（裸数组语义，与 /api-env-config/list 一致）
     */
    List<ApiGroupEnvConfigDto> list(Long groupId, String envCode);

    /**
     * 分组环境配置详情。
     *
     * @param id 配置 ID
     * @return 配置详情
     */
    ApiGroupEnvConfigDto get(Long id);

    /**
     * 创建或更新（按 group_id + env_code 唯一匹配）；命中则更新，未命中则插入。
     *
     * @param dto 配置入参（groupId / envCode / upstreamUrl 必填）
     * @return 保存后的配置
     */
    ApiGroupEnvConfigDto upsert(ApiGroupEnvConfigDto dto);

    /**
     * 更新可编辑字段（结构键 groupId / envCode 不可改）。
     *
     * @param id  配置 ID
     * @param dto 待更新字段
     */
    void update(Long id, ApiGroupEnvConfigDto dto);

    /**
     * 删除分组环境配置。
     *
     * @param id 配置 ID
     */
    void delete(Long id);

    /**
     * 连通性测试：对 upstreamUrl 发起一次轻量探测。
     *
     * <p>通过 → configStatus=2（已验证）；失败 → configStatus=1（已配置）。</p>
     *
     * @param id 配置 ID
     * @return 更新后的配置
     */
    ApiGroupEnvConfigDto testConnectivity(Long id);

    /**
     * 翻转 Mock 开关（0↔1）。
     *
     * @param id 配置 ID
     */
    void toggleMock(Long id);

    /**
     * 某分组在 4 个环境下的**生效配置**（含继承来源），供界面只读预览。
     *
     * <p>每个元素都可能来自：本分组自己的配置 / 某个祖先分组（sourcePath 标明链路）/ 无配置（DEFAULT）。
     * 与网关网关侧解析共用 {@code EnvConfigResolver}，保证"页面显示的"与"网关实际用的"完全一致。</p>
     *
     * @param groupId 分组 ID
     * @return 4 条生效配置（顺序 dev/test/pre/prod）
     */
    List<EffectiveEnvConfig> effective(Long groupId);
}
