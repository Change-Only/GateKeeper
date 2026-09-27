package com.gatekeeper.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gatekeeper.common.PageResult;
import com.gatekeeper.crypto.CryptoService;
import com.gatekeeper.entity.App;
import com.gatekeeper.entity.AppIpWhitelist;
import com.gatekeeper.entity.AppRateLimit;
import com.gatekeeper.exception.GatewayException;
import com.gatekeeper.mapper.AppIpWhitelistMapper;
import com.gatekeeper.mapper.AppMapper;
import com.gatekeeper.mapper.AppRateLimitMapper;
import com.gatekeeper.service.AppService;
import com.gatekeeper.util.CryptoKeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用管理服务实现 — 负责接入应用的分页查询、创建、更新、启停与删除，
 * 并管理应用级 IP 白名单、限流配置，以及生成/重置应用的 AppKey、AppSecret 凭证。
 *
 * <p>安全要点：AppSecret 落库前使用 AES-256 加密存储（密钥来自配置），
 * 列表查询时对 AppSecret 脱敏置空，仅创建/重置时返回一次明文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    private final AppIpWhitelistMapper ipWhitelistMapper;
    private final AppRateLimitMapper rateLimitMapper;
    private final CryptoService cryptoService;

    /** AppSecret 落库加密密钥（配置注入） */
    @Value("${gatekeeper.crypto.aes-key}")
    private String aesDbKey;

    /**
     * 分页查询应用列表，支持按应用名称模糊匹配与状态筛选
     *
     * @param current 当前页码
     * @param size    每页条数
     * @param appName 应用名称（模糊匹配，可为空）
     * @param status  应用状态（可为空，为空则不筛选）
     * @return 应用分页结果
     */
    @Override
    public PageResult<App> pageQuery(int current, int size, String appName, Integer status) {
        Page<App> page = new Page<>(current, size);
        QueryWrapper<App> wrapper = new QueryWrapper<>();
        if (appName != null && !appName.isEmpty()) {
            wrapper.like("app_name", appName); // 应用名称模糊匹配
        }
        if (status != null) {
            wrapper.eq("status", status); // 按状态精确筛选
        }
        wrapper.orderByDesc("created_at"); // 按创建时间倒序
        baseMapper.selectPage(page, wrapper);
        // 安全：列表响应不返回 AppSecret（明文仅在创建/重置时展示一次）
        page.getRecords().forEach(a -> a.setAppSecret(null));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 创建应用：生成 AppKey 与 AppSecret 凭证、初始化启用状态，
     * 并同步创建一条默认（不限流）的限流配置
     *
     * @param app 待创建的应用实体
     * @return 创建后的应用实体（含生成的凭证）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public App createApp(App app) {
        app.setAppKey(generateAppKey()); // 生成 32 位随机 AppKey
        String plainSecret = generateAppSecret(); // 生成 64 位随机 AppSecret（明文）
        app.setAppSecret(encryptSecret(plainSecret)); // 落库前 AES 加密
        app.setStatus(1); // 默认启用
        app.setCreatedAt(LocalDateTime.now());
        app.setUpdatedAt(LocalDateTime.now());
        baseMapper.insert(app);

        // 创建默认限流配置
        AppRateLimit rateLimit = new AppRateLimit();
        rateLimit.setAppId(app.getId());
        rateLimit.setQpsLimit(0); // 0 表示不限流
        rateLimit.setConcurrentLimit(0);
        rateLimit.setDailyLimit(0);
        rateLimit.setCreatedAt(LocalDateTime.now());
        rateLimitMapper.insert(rateLimit);

        // 仅本次响应返回明文 AppSecret（后续不可再查）
        app.setAppSecret(plainSecret);
        return app;
    }

    /**
     * 更新应用基本信息
     *
     * @param id  应用 ID
     * @param app 待更新的应用实体
     */
    @Override
    public void updateApp(Long id, App app) {
        app.setId(id);
        app.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(app);
    }

    /**
     * 更新应用启停状态
     *
     * @param id     应用 ID
     * @param status 目标状态（1 启用 / 0 停用）
     */
    @Override
    public void updateStatus(Long id, Integer status) {
        App app = new App();
        app.setId(id);
        app.setStatus(status);
        app.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(app);
    }

    /**
     * 删除应用
     *
     * @param id 应用 ID
     */
    @Override
    public void deleteApp(Long id) {
        baseMapper.deleteById(id);
    }

    /**
     * 查询指定应用的 IP 白名单列表（启用在前，其次按创建时间倒序）
     *
     * <p>T15-4：排序改为「status 倒序 + created_at 倒序」——
     * 启用中的规则是真正生效的那些，应当排在停用条目之前，
     * 免得用户翻着列表误以为"我配的几条没生效"。</p>
     *
     * @param appId 应用 ID
     * @return IP 白名单列表
     */
    @Override
    public List<AppIpWhitelist> listIpWhitelist(Long appId) {
        return ipWhitelistMapper.selectList(
                new QueryWrapper<AppIpWhitelist>()
                        .eq("app_id", appId)
                        .orderByDesc("status")
                        .orderByDesc("created_at"));
    }

    /**
     * 为指定应用新增一条 IP 白名单记录
     *
     * <p>T15-4：补 {@code envCode} / {@code status} 默认值。
     * 这两列在 DDL 上虽有默认值（'prod' / 1），但**不能依赖它**：
     * 网关 {@code IpWhitelistHandler} 按 {@code status = 1} 过滤，
     * 一旦出现 status 为 NULL 的行（例如从其它路径写入、或 DDL 默认值被改），
     * 该条规则会静默失效 —— 用户看到记录在列表里，实际却不参与校验。
     * 写入侧显式赋值是这条过滤能成立的前提。</p>
     *
     * @param appId     应用 ID
     * @param whitelist IP 白名单实体
     */
    @Override
    public void addIpWhitelist(Long appId, AppIpWhitelist whitelist) {
        whitelist.setAppId(appId);
        whitelist.setCreatedAt(LocalDateTime.now());
        if (whitelist.getStatus() == null) {
            whitelist.setStatus(AppIpWhitelist.STATUS_ENABLED);
        }
        if (whitelist.getEnvCode() == null || whitelist.getEnvCode().trim().isEmpty()) {
            whitelist.setEnvCode(AppIpWhitelist.DEFAULT_ENV_CODE);
        }
        ipWhitelistMapper.insert(whitelist);
    }

    /**
     * 更新一条 IP 白名单（IP/CIDR、备注、环境、启用停用）。
     *
     * <p>T15-4 新增：此前只有"新增 / 删除"两个动作，想临时放行某段 IP
     * 只能删记录再加回来（丢备注、易写错）。补上编辑与启停后
     * {@code status} 才真正可用（网关侧已按 status=1 过滤）。</p>
     *
     * <p>实现用 {@code updateById} 而非「先删后插」：本表主键是被前端引用的
     * （删除按钮按 id 提交），换 id 会让页面上的行"变成新记录"。
     * 因此这里不追求把 null 写库 —— {@code appId} 与 {@code createdAt} 本就不允许被改，
     * 备注清空属于次要场景，用 trim + 空串归一即可（空串在 UI 上等价于未填）。</p>
     *
     * @param whitelistId 白名单记录 ID
     * @param whitelist   新值
     */
    @Override
    public void updateIpWhitelist(Long whitelistId, AppIpWhitelist whitelist) {
        if (whitelistId == null) {
            throw GatewayException.badRequest("白名单ID不能为空");
        }
        if (whitelist == null) {
            throw GatewayException.badRequest("白名单内容不能为空");
        }
        AppIpWhitelist existing = ipWhitelistMapper.selectById(whitelistId);
        if (existing == null) {
            throw GatewayException.notFound("IP 白名单不存在");
        }
        AppIpWhitelist patch = new AppIpWhitelist();
        patch.setId(whitelistId);
        // 归属应用不可改：沿用库里的值，杜绝把某应用的规则"搬"到另一个应用
        patch.setAppId(existing.getAppId());
        patch.setIpCidr(StringUtils.hasText(whitelist.getIpCidr())
                ? whitelist.getIpCidr().trim() : existing.getIpCidr());
        patch.setRemark(whitelist.getRemark() == null ? existing.getRemark() : whitelist.getRemark().trim());
        patch.setEnvCode(StringUtils.hasText(whitelist.getEnvCode())
                ? whitelist.getEnvCode().trim() : AppIpWhitelist.DEFAULT_ENV_CODE);
        patch.setStatus(whitelist.getStatus() == null
                ? AppIpWhitelist.STATUS_ENABLED : whitelist.getStatus());
        ipWhitelistMapper.updateById(patch);
    }

    /**
     * 删除指定 IP 白名单记录
     *
     * @param whitelistId 白名单记录 ID
     */
    @Override
    public void removeIpWhitelist(Long whitelistId) {
        ipWhitelistMapper.deleteById(whitelistId);
    }

    /**
     * 查询指定应用的限流配置
     *
     * @param appId 应用 ID
     * @return 限流配置实体，不存在时返回 null
     */
    @Override
    public AppRateLimit getRateLimit(Long appId) {
        return rateLimitMapper.selectOne(
                new QueryWrapper<AppRateLimit>().eq("app_id", appId));
    }

    /**
     * 更新指定应用的限流配置；若配置不存在则新建一条
     *
     * @param appId     应用 ID
     * @param rateLimit 限流配置实体
     */
    @Override
    public void updateRateLimit(Long appId, AppRateLimit rateLimit) {
        AppRateLimit existing = rateLimitMapper.selectOne(
                new QueryWrapper<AppRateLimit>().eq("app_id", appId));
        if (existing != null) {
            // 已有配置则覆盖限流阈值
            existing.setQpsLimit(rateLimit.getQpsLimit());
            existing.setConcurrentLimit(rateLimit.getConcurrentLimit());
            existing.setDailyLimit(rateLimit.getDailyLimit());
            existing.setUpdatedAt(LocalDateTime.now());
            rateLimitMapper.updateById(existing);
        } else {
            // 无配置则新建
            rateLimit.setAppId(appId);
            rateLimit.setCreatedAt(LocalDateTime.now());
            rateLimitMapper.insert(rateLimit);
        }
    }
    /**
     * AppSecret 明文 AES-256 加密（ECB/PKCS5Padding），密钥取自配置
     *
     * @param plainSecret 明文密钥
     * @return Base64 密文
     */
    private String encryptSecret(String plainSecret) {
        String key = CryptoKeyUtil.toBase64Key(aesDbKey);
        return cryptoService.encrypt("AES", plainSecret, key, null, "ECB", "PKCS5Padding");
    }

    private String generateAppKey() {
        return IdUtil.fastSimpleUUID(); // 生成 32 位无横线 UUID 作为 AppKey
    }

    private String generateAppSecret() {
        return IdUtil.fastSimpleUUID() + IdUtil.fastSimpleUUID(); // 两个 UUID 拼接生成 64 位 AppSecret
    }
}
