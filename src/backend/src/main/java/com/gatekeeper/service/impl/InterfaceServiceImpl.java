package com.gatekeeper.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.dto.ApiChangeLogDto;
import com.gatekeeper.dto.ApiEnvConfigDto;
import com.gatekeeper.dto.ApiParamDto;
import com.gatekeeper.dto.ApiVersionDto;
import com.gatekeeper.dto.InterfaceDetailVo;
import com.gatekeeper.dto.InterfaceListVo;
import com.gatekeeper.entity.ApiChangeLog;
import com.gatekeeper.entity.ApiEnvConfig;
import com.gatekeeper.entity.ApiGroup;
import com.gatekeeper.entity.ApiInterface;
import com.gatekeeper.entity.ApiParam;
import com.gatekeeper.entity.ApiVersion;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.ApiChangeLogMapper;
import com.gatekeeper.mapper.ApiEnvConfigMapper;
import com.gatekeeper.mapper.ApiGroupMapper;
import com.gatekeeper.mapper.ApiInterfaceMapper;
import com.gatekeeper.mapper.ApiParamMapper;
import com.gatekeeper.mapper.ApiVersionMapper;
import com.gatekeeper.service.InterfaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接口管理服务实现 — 负责 API 接口的分页查询、创建、更新、启停与删除。
 *
 * <p>T03b 扩展：列表带分组名、详情聚合、删除前置校验（版本全下线）。</p>
 *
 * <p>分组筛选语义：{@code groupId} 按「自身 + 全部子孙分组」展开（详见
 * {@link #applyGroupScope}），选中父分组即可看到其下所有层级的接口。</p>
 *
 * @author GateKeeper
 * @since T03b (APIM V2)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterfaceServiceImpl extends ServiceImpl<ApiInterfaceMapper, ApiInterface> implements InterfaceService {

    /** paramType：Header */
    private static final int PT_HEADER = 1;
    /** paramType：Request 入参 */
    private static final int PT_REQUEST = 3;
    /** paramType：Response 出参 */
    private static final int PT_RESPONSE = 4;
    /** paramType：Error 错误码 */
    private static final int PT_ERROR = 5;

    /** 变更历史详情聚合：最近 N 条 */
    private static final int RECENT_CHANGE_LOG_LIMIT = 5;

    private final ApiGroupMapper apiGroupMapper;
    private final ApiParamMapper apiParamMapper;
    private final ApiVersionMapper apiVersionMapper;
    private final ApiEnvConfigMapper apiEnvConfigMapper;
    private final ApiChangeLogMapper apiChangeLogMapper;
    /** T17：interface_path 的字段级加解密（含盲索引） */
    private final com.gatekeeper.crypto.InterfaceCryptoService interfaceCryptoService;
    /** T17：控制台可见性判定（每次请求解析一次，按行复用） */
    private final com.gatekeeper.service.InterfaceVisibilityService interfaceVisibilityService;

    @Override
    public PageResult<ApiInterface> pageQuery(int current, int size, String interfaceName, Long groupId) {
        Page<ApiInterface> page = new Page<>(current, size);
        QueryWrapper<ApiInterface> wrapper = new QueryWrapper<>();
        if (interfaceName != null && !interfaceName.isEmpty()) {
            wrapper.like("interface_name", interfaceName); // 接口名称模糊匹配
        }
        applyGroupScope(wrapper, groupId); // 按分组筛选（含全部子孙分组）
        wrapper.orderByDesc("created_at"); // 按创建时间倒序
        baseMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public PageResult<InterfaceListVo> pageQueryEnriched(int current, int size, String interfaceName, Long groupId) {
        // 入参兜底
        int pageNum = current < 1 ? 1 : current;
        int pageSize = size < 1 ? 10 : size;
        Page<ApiInterface> page = new Page<>(pageNum, pageSize);
        QueryWrapper<ApiInterface> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(interfaceName)) {
            wrapper.like("interface_name", interfaceName);
        }
        applyGroupScope(wrapper, groupId); // 按分组筛选（含全部子孙分组）
        wrapper.orderByDesc("created_at");
        baseMapper.selectPage(page, wrapper);

        List<ApiInterface> rows = page.getRecords();
        Map<Long, String> groupNames = loadGroupNames(rows);
        // T17：可见性上下文每页解析一次（不是每行一次），每行只剩纯内存判断
        com.gatekeeper.security.InterfaceViewer viewer = interfaceVisibilityService.resolveViewer();
        List<InterfaceListVo> vos = new ArrayList<>(rows.size());
        for (ApiInterface r : rows) {
            InterfaceListVo vo = toListVo(r, groupNames);
            // 路径出参：白名单内给明文、其余给固定掩码（读路径统一在此收敛）
            applyPathVisibility(vo, r.getOwnerId(), viewer);
            vos.add(vo);
        }
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 列表行的路径可见性处理（T17）。
     *
     * <p>只在 VO 上替换，<b>不改动实体</b> —— 实体后续可能被同一次请求的其他逻辑复用，
     * 就地改写会埋下"某些分支拿到明文、某些拿到掩码"的隐性 bug。</p>
     *
     * @param vo      待出参的列表行
     * @param ownerId 该行接口的负责人 id（owner 本人始终可见）
     * @param viewer  本次请求的可见性上下文
     */
    private void applyPathVisibility(InterfaceListVo vo, Long ownerId,
                                     com.gatekeeper.security.InterfaceViewer viewer) {
        String plain = interfaceCryptoService.decryptField(vo.getInterfacePath());
        if (viewer.mustMask(ownerId)) {
            vo.setInterfacePath(interfaceCryptoService.maskPath());
            vo.setPathMasked(Boolean.TRUE);
        } else {
            vo.setInterfacePath(plain);
            vo.setPathMasked(Boolean.FALSE);
        }
    }

    @Override
    public ApiInterface createInterface(ApiInterface apiInterface) {
        requireGroup(apiInterface); // T13：所属分组必填
        apiInterface.setStatus(1); // 默认启用
        if (apiInterface.getTimeoutMs() == null) {
            apiInterface.setTimeoutMs(5000); // 默认超时 5000 毫秒
        }
        // T03b：发布态默认草稿（0），避免未定义参数即被当作已发布
        if (apiInterface.getPublishStatus() == null) {
            apiInterface.setPublishStatus(0);
        }
        if (apiInterface.getVisibility() == null) {
            apiInterface.setVisibility(1);
        }
        if (apiInterface.getAuthRequired() == null) {
            apiInterface.setAuthRequired(1);
        }
        if (apiInterface.getTransportSecurity() == null) {
            apiInterface.setTransportSecurity("NONE");
        }
        if (apiInterface.getGrantCount() == null) {
            apiInterface.setGrantCount(0);
        }
        apiInterface.setCreatedAt(LocalDateTime.now());
        apiInterface.setUpdatedAt(LocalDateTime.now());
        // T17 写路径：interface_path 加密落库 + 盲索引就位（开关关闭时存明文，但 blind index 照算）
        interfaceCryptoService.applyToInterface(apiInterface);
        baseMapper.insert(apiInterface);
        // 落库完成后立即还原明文：返回值会直接进响应体，不能把 enc:v1: 串露给前端。
        // 提交者本人刚输入的路径，回显明文不构成泄漏（他本来就知道）。
        interfaceCryptoService.decryptInPlace(apiInterface);
        log.info("ApiInterface created: id={}, name={}", apiInterface.getId(), apiInterface.getInterfaceName());
        return apiInterface;
    }

    @Override
    public void updateInterface(Long id, ApiInterface apiInterface) {
        requireGroup(apiInterface); // T13：所属分组必填
        if (id == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        ApiInterface existing = baseMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.notFound("接口不存在: id=" + id);
        }
        apiInterface.setId(id);
        applyPathWriteBackGuard(apiInterface, existing);
        apiInterface.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(apiInterface);
    }

    /**
     * 掩码回写防线 + 写路径加密（T17）。
     *
     * <p><b>为什么必须有这道防线</b>：不在可见性白名单内的用户打开编辑弹窗时，
     * 表单里的路径就是掩码 {@code ****}。他「什么都不改直接保存」时，
     * 后端若照单全收就会把 {@code ****} 当成新路径写进库 ——
     * <b>接口路径被静默改坏，网关随即对这条接口永久 404</b>，且没有任何报错。
     * （同族缺陷见 docs/CONTRACTS §8「表单往返静默改写」。）</p>
     *
     * <p>因此：入参为空或等于掩码 ⇒ 视为「本次不改这一列」，
     * 用库中现存的密文值回填，使这次 update 对该列成为 no-op。</p>
     *
     * @param incoming 前端提交的实体
     * @param existing 库中现存行（其 interface_path 为密文或明文原值）
     */
    private void applyPathWriteBackGuard(ApiInterface incoming, ApiInterface existing) {
        String path = incoming.getInterfacePath();
        if (interfaceCryptoService.isMask(path) || !StringUtils.hasText(path)) {
            incoming.setInterfacePath(existing.getInterfacePath());   // 已是密文 → applyToInterface 不会二次加密
            incoming.setInterfacePathHash(existing.getInterfacePathHash());
            return;
        }
        interfaceCryptoService.applyToInterface(incoming);
    }

    /**
     * T13（用户需求第 3 条）：接口所属分组为必填项。
     *
     * <p><b>为什么后端也要拦</b>：前端表单加 {@code required} 只是「体验层闸门」，
     * curl / 脚本仍可绕过去造出无分组接口；而分组正是 T13「环境配置按分组维护 + 向上继承」
     * 的锚点 —— 没有分组的接口**永远拿不到任何环境配置**（继承链起点为空），
     * 属于会静默影响线上转发目标的脏数据。故两端同时拦。</p>
     *
     * <p>同时校验分组真实存在，避免落一个悬空 group_id（同 t13 之前修过的悬空授权问题）。</p>
     *
     * @param apiInterface 待写入的接口
     * @throws com.gatekeeper.exception.GatewayException 400 当分组为空或不存在
     */
    private void requireGroup(ApiInterface apiInterface) {
        if (apiInterface == null) {
            throw com.gatekeeper.exception.GatewayException.badRequest("请求体不能为空");
        }
        if (apiInterface.getGroupId() == null) {
            throw com.gatekeeper.exception.GatewayException.badRequest("请选择所属分组（必填）");
        }
        if (apiGroupMapper.selectById(apiInterface.getGroupId()) == null) {
            throw com.gatekeeper.exception.GatewayException.badRequest(
                    "所属分组不存在: id=" + apiInterface.getGroupId());
        }
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        ApiInterface iface = new ApiInterface();
        iface.setId(id);
        iface.setStatus(status);
        iface.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(iface);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteInterface(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        ApiInterface api = baseMapper.selectById(id);
        if (api == null) {
            throw GatewayException.notFound("接口不存在: id=" + id);
        }
        // 前置校验：必须已下线全部版本（仅 status=3 已下线 的版本不算占用）
        Long active = apiVersionMapper.selectCount(
                new QueryWrapper<ApiVersion>().eq("api_id", id).ne("status", 3));
        if (active != null && active > 0L) {
            throw GatewayException.badRequest("该接口仍有 " + active + " 个未下线版本，请先下线全部版本再删除");
        }
        baseMapper.deleteById(id);
        log.info("ApiInterface deleted: id={}, name={}", id, api.getInterfaceName());
    }

    @Override
    public InterfaceDetailVo getDetail(Long id) {
        if (id == null) {
            throw GatewayException.badRequest("接口ID不能为空");
        }
        ApiInterface api = baseMapper.selectById(id);
        if (api == null) {
            throw GatewayException.notFound("接口不存在: id=" + id);
        }
        InterfaceDetailVo vo = new InterfaceDetailVo();
        // T17：详情页的路径与参数也走同一套可见性口径（与列表页一致，避免"列表掩码、详情明文"的漏口）
        com.gatekeeper.security.InterfaceViewer viewer = interfaceVisibilityService.resolveViewer();
        boolean maskPath = viewer.mustMask(api.getOwnerId());
        api.setInterfacePath(maskPath
                ? interfaceCryptoService.maskPath()
                : interfaceCryptoService.decryptField(api.getInterfacePath()));
        vo.setApiPathMasked(maskPath);
        vo.setApi(api);

        boolean maskParams = maskPath; // 同一行（同一 owner）⇒ 路径与参数的可见性必然一致
        vo.setHeader(paramDtos(id, PT_HEADER, maskParams));
        vo.setRequest(paramDtos(id, PT_REQUEST, maskParams));
        vo.setResponse(paramDtos(id, PT_RESPONSE, maskParams));
        vo.setError(paramDtos(id, PT_ERROR, maskParams));
        vo.setParamsMasked(maskParams);
        vo.setVersions(versionDtos(id));
        vo.setEnvConfigs(envConfigDtos(id));
        vo.setRecentChangeLogs(changeLogDtos(id, RECENT_CHANGE_LOG_LIMIT));
        return vo;
    }

    // =====================================================================
    // 内部工具
    // =====================================================================

    /**
     * 按分组筛选条件（含全部子孙分组）。
     *
     * <p>选中父分组时，需要看到它下面所有层级的接口，而不是只看到直接挂在该
     * 分组上的接口。因此这里先把 groupId 展开成「自身 + 全部子孙分组」的 ID
     * 集合，再以 IN 条件筛选。</p>
     *
     * <p>当集合只有 1 个元素（叶子分组，或该分组无任何子分组）时退化为
     * {@code eq}，保持既有 SQL 形状不变，避免无谓地改变执行计划。</p>
     *
     * @param wrapper 查询条件构造器
     * @param groupId 分组 ID，为 null 时不追加任何条件
     */
    private void applyGroupScope(QueryWrapper<ApiInterface> wrapper, Long groupId) {
        if (groupId == null) {
            return;
        }
        Set<Long> groupIds = groupIdWithDescendants(groupId);
        if (groupIds.size() == 1) {
            wrapper.eq("group_id", groupId); // 叶子分组 == 精确匹配
        } else {
            wrapper.in("group_id", groupIds); // 父分组 == 自身 + 全部子孙
        }
    }

    /**
     * 收集指定分组及其全部子孙分组的 ID。
     *
     * @param groupId 分组 ID
     * @return 含自身的分组 ID 集合
     */
    private Set<Long> groupIdWithDescendants(Long groupId) {
        Set<Long> ids = new HashSet<>();
        collectGroupIds(groupId, ids);
        return ids;
    }

    /**
     * 递归收集分组 ID（含环路保护）。
     *
     * @param groupId 当前分组 ID
     * @param ids     已收集的 ID 集合
     */
    private void collectGroupIds(Long groupId, Set<Long> ids) {
        if (groupId == null || !ids.add(groupId)) {
            return; // 为空或已收集过则停止，避免数据环路导致死递归
        }
        List<ApiGroup> children = apiGroupMapper.selectList(
                new QueryWrapper<ApiGroup>().eq("parent_id", groupId));
        for (ApiGroup child : children) {
            collectGroupIds(child.getId(), ids);
        }
    }

    private Map<Long, String> loadGroupNames(List<ApiInterface> rows) {
        Set<Long> ids = new HashSet<>();
        for (ApiInterface r : rows) {
            if (r.getGroupId() != null) {
                ids.add(r.getGroupId());
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ApiGroup> groups = apiGroupMapper.selectBatchIds(ids);
        Map<Long, String> map = new HashMap<>();
        if (groups != null) {
            for (ApiGroup g : groups) {
                map.put(g.getId(), g.getGroupName());
            }
        }
        return map;
    }

    private InterfaceListVo toListVo(ApiInterface r, Map<Long, String> groupNames) {
        InterfaceListVo vo = new InterfaceListVo();
        BeanUtils.copyProperties(r, vo);
        if (r.getGroupId() != null) {
            vo.setGroupName(groupNames.get(r.getGroupId()));
        }
        return vo;
    }

    /**
     * 读取某分区的参数并转 DTO（T17：附带按可见性解密 / 掩码）。
     *
     * <p>掩码只作用在「契约内容」三列（fieldName / example / description）；
     * 结构列保持真值。此口径与 {@code ApiParamServiceImpl.toVisibleDto} 及
     * 接口文档导出（{@code AppInterfaceDocServiceImpl.toItem}）必须一致 —— 三处同改。</p>
     *
     * @param apiId      接口 id
     * @param paramType  参数类型
     * @param maskParams true=内容列替换为固定掩码
     */
    private List<ApiParamDto> paramDtos(Long apiId, int paramType, boolean maskParams) {
        List<ApiParam> list = apiParamMapper.selectList(
                new QueryWrapper<ApiParam>()
                        .eq("api_id", apiId)
                        .eq("param_type", paramType)
                        .orderByAsc("sort_order")
                        .orderByAsc("id"));
        List<ApiParamDto> dtos = new ArrayList<>(list.size());
        for (ApiParam p : list) {
            ApiParamDto d = new ApiParamDto();
            BeanUtils.copyProperties(p, d);
            if (maskParams) {
                d.setFieldName(interfaceCryptoService.maskPath());
                d.setExample(interfaceCryptoService.maskPath());
                d.setDescription(interfaceCryptoService.maskPath());
                d.setMasked(Boolean.TRUE);
            } else {
                d.setFieldName(interfaceCryptoService.decryptField(p.getFieldName()));
                d.setExample(interfaceCryptoService.decryptField(p.getExample()));
                d.setDescription(interfaceCryptoService.decryptField(p.getDescription()));
                d.setMasked(Boolean.FALSE);
            }
            dtos.add(d);
        }
        return dtos;
    }

    private List<ApiVersionDto> versionDtos(Long apiId) {
        List<ApiVersion> list = apiVersionMapper.selectList(
                new QueryWrapper<ApiVersion>().eq("api_id", apiId).orderByAsc("id"));
        List<ApiVersionDto> dtos = new ArrayList<>(list.size());
        for (ApiVersion v : list) {
            ApiVersionDto d = new ApiVersionDto();
            BeanUtils.copyProperties(v, d);
            dtos.add(d);
        }
        return dtos;
    }

    private List<ApiEnvConfigDto> envConfigDtos(Long apiId) {
        List<ApiEnvConfig> list = apiEnvConfigMapper.selectList(
                new QueryWrapper<ApiEnvConfig>().eq("api_id", apiId).orderByAsc("id"));
        List<ApiEnvConfigDto> dtos = new ArrayList<>(list.size());
        for (ApiEnvConfig e : list) {
            ApiEnvConfigDto d = new ApiEnvConfigDto();
            BeanUtils.copyProperties(e, d);
            dtos.add(d);
        }
        return dtos;
    }

    private List<ApiChangeLogDto> changeLogDtos(Long apiId, int limit) {
        List<ApiChangeLog> list = apiChangeLogMapper.selectList(
                new QueryWrapper<ApiChangeLog>()
                        .eq("api_id", apiId)
                        .orderByDesc("create_time")
                        .last("LIMIT " + limit));
        List<ApiChangeLogDto> dtos = new ArrayList<>(list.size());
        for (ApiChangeLog c : list) {
            ApiChangeLogDto d = new ApiChangeLogDto();
            BeanUtils.copyProperties(c, d);
            dtos.add(d);
        }
        return dtos;
    }
}
