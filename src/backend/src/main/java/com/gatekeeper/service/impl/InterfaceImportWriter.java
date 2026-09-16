package com.gatekeeper.service.impl;

import com.gatekeeper.dto.ApiParamBatchSaveRequest;
import com.gatekeeper.dto.ApiParamBatchSaveResult;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.openapi.ParsedOperation;
import com.gatekeeper.service.ApiParamService;
import com.gatekeeper.service.InterfaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单条接口导入的写入器（T18）—— <b>刻意从 {@link InterfaceImportServiceImpl} 里拆出来的一个 Bean</b>。
 *
 * <h3>为什么必须是独立 Bean</h3>
 * <p>要的是「一条 operation 的接口行 + 它的参数行」<b>要么都写、要么都不写</b>。
 * 若把 {@code @Transactional} 打在 {@code InterfaceImportServiceImpl} 自己的私有方法上，
 * 调用走的是 {@code this.xxx()}，<b>不经过 Spring 代理</b>，注解形同虚设；
 * 打到 {@code importSpec} 整体上又会变成「一条失败 → 整批回滚」，与
 * 「部分成功」的要求相反。拆成独立 Bean 后，每次调用都是真正的跨代理调用，
 * 事务边界正好落在<b>单条 operation</b> 上。</p>
 *
 * <h3>回滚范围</h3>
 * <p>接口行与 4 个分区的参数行同处一个事务：参数落库失败（如字段超长、掩码回写被拒）
 * 会连同刚插入的接口行一起回滚。否则会留下「列表里有一条接口，
 * 但结果里这条是 FAILED」的自相矛盾状态 —— 用户看得见却不知道该不该删。</p>
 *
 * <p>⚠ 本类只被 {@link InterfaceImportServiceImpl} 调用，不要直接暴露到 Controller：
 * 它不带任何权限校验，也不做去重。</p>
 *
 * @author GateKeeper
 * @since T18 (OpenAPI 导入)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterfaceImportWriter {

    /** 导入产物的标签，便于在列表里一眼区分「人手建的」与「文档导入的」 */
    public static final String IMPORT_TAG = "openapi-import";

    private final InterfaceService interfaceService;
    private final ApiParamService apiParamService;

    /**
     * 写入一条 operation（接口 + 4 类参数），单条事务。
     *
     * @param op           解析产物
     * @param groupId      目标分组 id（已由调用方校验存在）
     * @param apiCode      接口编码（已由调用方生成并查重）
     * @param operatorId   操作人 id，可为 null
     * @param operatorName 操作人姓名，可为 null
     * @return 写入结果（接口 id + 参数条数）
     */
    @Transactional(rollbackFor = Exception.class)
    public WriteOutcome writeOne(ParsedOperation op, Long groupId, String apiCode,
                                 Long operatorId, String operatorName) {
        ApiInterface created = interfaceService.createInterface(
                toEntity(op, groupId, apiCode, operatorId, operatorName));

        ApiParamBatchSaveRequest paramReq = new ApiParamBatchSaveRequest();
        paramReq.setApiId(created.getId());
        paramReq.setHeader(op.getHeader());
        paramReq.setRequest(op.getRequest());
        paramReq.setResponse(op.getResponse());
        paramReq.setError(op.getError());
        ApiParamBatchSaveResult saved = apiParamService.batchSave(paramReq);

        int paramCount = saved == null ? 0 : saved.getTotal();
        log.info("OpenAPI operation imported: apiId={}, method={}, paramCount={}",
                created.getId(), op.getMethod(), paramCount);
        return new WriteOutcome(created.getId(), paramCount);
    }

    /**
     * 解析产物 → 接口实体。
     *
     * <p>刻意留空的三个字段，各有理由：</p>
     * <ul>
     *   <li>{@code backendUrl} 传<b>空串而非 null</b>：该列 {@code NOT NULL}，
     *       null 会直接撞 {@code Column 'backend_url' cannot be null} 变成 500。
     *       为什么不填 spec 的 {@code servers[0].url}：那是"对外基地址"，
     *       常常就是网关自己的域名，当转发目标会让流量回到网关自己（回环）。</li>
     *   <li>{@code currentVersion} 不写：该列语义是"冗余自 api_version.is_current"。
     *       导入<b>刻意不建 api_version 行</b>，若这里填了 {@code info.version}，
     *       「概览显示 1.0.0、版本管理却空空如也」会让人以为数据坏了。</li>
     *   <li>{@code publishStatus} 不写：由 {@code createInterface} 兜底为 0（草稿），
     *       导入的接口必须由人显式发布 —— 否则一份文档就能把流量放行到未配置的上游。</li>
     * </ul>
     */
    private ApiInterface toEntity(ParsedOperation op, Long groupId, String apiCode,
                                  Long operatorId, String operatorName) {
        ApiInterface e = new ApiInterface();
        e.setInterfaceName(op.getName());
        e.setInterfacePath(op.getPath());
        e.setRequestMethod(op.getMethod());
        e.setRequestParamType(op.getRequestParamType());
        e.setGroupId(groupId);
        e.setBackendUrl("");
        e.setDescription(op.getDescription());
        e.setApiCode(apiCode);
        e.setOwnerId(operatorId);
        e.setOwnerName(operatorName);
        e.setTags(IMPORT_TAG);
        e.setStatus(1);
        return e;
    }

    /**
     * 单位写入结果。
     */
    public static final class WriteOutcome {

        /** 新建的接口 id */
        private final Long apiId;

        /** 实际写入的参数条数 */
        private final int paramCount;

        WriteOutcome(Long apiId, int paramCount) {
            this.apiId = apiId;
            this.paramCount = paramCount;
        }

        public Long getApiId() {
            return apiId;
        }

        public int getParamCount() {
            return paramCount;
        }
    }
}
